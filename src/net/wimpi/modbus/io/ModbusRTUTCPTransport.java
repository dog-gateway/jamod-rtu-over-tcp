/**
 * 
 */
package net.wimpi.modbus.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.msg.ModbusMessage;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.util.ModbusUtil;

/**
 * @author bonino
 * 
 */
public class ModbusRTUTCPTransport implements ModbusTransport
{

    public static final String logId = "[ModbusRTUTCPTransport]: ";

    // The input stream from which reading the Modbus frames
    private DataInputStream inputStream;

    // The output stream to which writing the Modbus frames
    private DataOutputStream outputStream;

    // The Bytes output stream to use as output buffer for Modbus frames
    private BytesOutputStream outputBuffer;

    // The BytesInputStream wrapper for the transport input stream
    private BytesInputStream inputBuffer;

    // The last request sent over the transport ?? useful ??
    private byte[] lastRequest = null;

    // the socket used by this transport
    private Socket socket;

    // the read timeout timer
    private Timer readTimeoutTimer;

    // the read timout
    private int readTimeout = 5000; // ms

    // the timeou flag
    private boolean isTimedOut;

    /**
     * @param socket
     * @throws IOException
     * 
     */
    public ModbusRTUTCPTransport(Socket socket) throws IOException
    {
        // prepare the input and output streams...
        if (socket != null)
            this.setSocket(socket);

        // set the timed out flag at false
        this.isTimedOut = false;
    }

    /**
     * Stores the given {@link Socket} instance and prepares the related streams
     * to use them for Modbus RTU over TCP communication.
     * 
     * @param socket
     * @throws IOException
     */
    public void setSocket(Socket socket) throws IOException
    {
        if (this.socket != null)
        {
            // TODO: handle clean closure of the streams
            this.outputBuffer.close();
            this.inputBuffer.close();
            this.inputStream.close();
            this.outputStream.close();
        }

        // store the socket used by this transport
        this.socket = socket;

        // get the input and output streams
        this.inputStream = new DataInputStream(socket.getInputStream());
        this.outputStream = new DataOutputStream(this.socket.getOutputStream());

        // prepare the buffers
        this.outputBuffer = new BytesOutputStream(Modbus.MAX_MESSAGE_LENGTH);
        this.inputBuffer = new BytesInputStream(Modbus.MAX_MESSAGE_LENGTH);
    }

    /**
     * writes the given ModbusMessage over the physical transport handled by
     * this object.
     * 
     * @param msg
     *            the {@link ModbusMessage} to be written on the transport.
     */
    @Override
    public synchronized void writeMessage(ModbusMessage msg)
            throws ModbusIOException
    {
        try
        {
            // atomic access to the output buffer
            synchronized (this.outputBuffer)
            {
                // reset the output buffer
                this.outputBuffer.reset();

                // prepare the message for "virtual" serial transport
                msg.setHeadless();

                // write the message to the output buffer
                msg.writeTo(this.outputBuffer);

                // compute the CRC
                int[] crc = ModbusUtil.calculateCRC(
                        this.outputBuffer.getBuffer(), 0,
                        this.outputBuffer.size());

                // write the CRC on the output buffer
                this.outputBuffer.writeByte(crc[0]);
                this.outputBuffer.writeByte(crc[1]);

                // store the buffer length
                int bufferLength = this.outputBuffer.size();

                // store the raw output buffer reference
                byte rawBuffer[] = this.outputBuffer.getBuffer();

                // write the buffer on the socket
                this.outputStream.write(rawBuffer, 0, bufferLength); // PDU +
                                                                     // CRC
                this.outputStream.flush();

                // debug
                if (Modbus.debug)
                    System.out.println("Sent: "
                            + ModbusUtil.toHex(rawBuffer, 0, bufferLength));

                // store the written buffer as the last request
                this.lastRequest = new byte[bufferLength];
                System.arraycopy(rawBuffer, 0, this.lastRequest, 0,
                        bufferLength);

                // sleep for the time needed to receive the request at the other
                // point of the connection
                Thread.sleep(bufferLength);
            }

        }
        catch (Exception ex)
        {
            throw new ModbusIOException("I/O failed to write");
        }

    }// writeMessage

    // This is required for the slave that is not supported
    @Override
    public synchronized ModbusRequest readRequest() throws ModbusIOException
    {
        throw new RuntimeException("Operation not supported.");
    } // readRequest

    @Override
    /**
     * Lazy implementation: avoid CRC validation...
     */
    public synchronized ModbusResponse readResponse() throws ModbusIOException
    {
        // the received response
        ModbusResponse response = null;

        // reset the timed out flag
        this.isTimedOut = false;

        // init and start the timeout timer
        this.readTimeoutTimer = new Timer();
        this.readTimeoutTimer.schedule(new TimerTask()
        {

            @Override
            public void run()
            {
                isTimedOut = true;
            }
        }, this.readTimeout);

        try
        {
            // atomic access to the input buffer
            synchronized (inputBuffer)
            {
                // clean the input buffer
                inputBuffer.reset(new byte[Modbus.MAX_MESSAGE_LENGTH]);

                // sleep for the time needed to receive the first part of the
                // response
                int available = this.inputStream.available();
                while ((available < 4) && (!this.isTimedOut))
                {
                    Thread.yield(); // 1ms * #bytes (4bytes in the worst case)
                    available = this.inputStream.available();

                    if (Modbus.debug)
                        System.out.println("Available bytes: " + available);
                }

                // check if timedOut
                if (this.isTimedOut)
                    throw new ModbusIOException(
                            "I/O exception - read timeout.\n");

                // get a reference to the inner byte buffer
                byte inBuffer[] = this.inputBuffer.getBuffer();

                // read the first 2 bytes from the input stream
                this.inputStream.read(inBuffer, 0, 2);
                // this.inputStream.readFully(inBuffer);

                // read the progressive id
                int packetId = inputBuffer.readUnsignedByte();

                // debug
                if (Modbus.debug)
                    System.out.println(ModbusRTUTCPTransport.logId
                            + "Read packet with progressive id: " + packetId);

                // read the function code
                int functionCode = inputBuffer.readUnsignedByte();

                // debug
                if (Modbus.debug)
                    System.out.println(" uid: " + packetId + ", function code: "
                            + functionCode);

                // compute the number of bytes composing the message (including
                // the CRC = 2bytes)
                LengthOffset packetLengthOffest = this
                        .computePacketLength(functionCode);

                // get the packet length
                int packetLength = packetLengthOffest.getLength();

                if (packetLength > 0)
                {

                    // sleep for the time needed to receive the first part of
                    // the
                    // response
                    while ((this.inputStream.available() < (packetLength - 3))
                            && (!this.isTimedOut))
                    {
                        try
                        {
                            Thread.sleep(10);
                        }
                        catch (InterruptedException ie)
                        {
                            // do nothing
                            System.err.println(
                                    "Sleep interrupted while waiting for response body...\n"
                                            + ie);
                        }
                    }

                    // check if timedOut
                    if (this.isTimedOut)
                        throw new ModbusIOException(
                                "I/O exception - read timeout.\n");

                    // read the remaining bytes
                    this.inputStream.read(inBuffer,
                            2 + packetLengthOffest.getOffset(), packetLength);

                    // debug
                    if (Modbus.debug)
                        System.out.println(" bytes: "
                                + ModbusUtil.toHex(inBuffer, 0, packetLength)
                                + ", desired length: " + packetLength);

                    // compute the CRC
                    int crc[] = ModbusUtil.calculateCRC(inBuffer, 0,
                            packetLength - 2);

                    // check the CRC against the received one...
                    if (packetLength < 2
                            || ModbusUtil.unsignedByteToInt(
                                    inBuffer[packetLength - 2]) != crc[0]
                            || ModbusUtil.unsignedByteToInt(
                                    inBuffer[packetLength - 1]) != crc[1])
                    {
                        throw new IOException("CRC Error in received frame: "
                                + packetLength + " bytes: "
                                + ModbusUtil.toHex(inBuffer, 0, packetLength));
                    }

                    // reset the input buffer to the given packet length
                    // (excluding
                    // the CRC)
                    this.inputBuffer.reset(inBuffer, packetLength - 2);

                    // create the response
                    response = ModbusResponse
                            .createModbusResponse(functionCode);
                    response.setHeadless();

                    // read the response
                    response.readFrom(inputBuffer);
                }
                else
                {
                    throw new IOException(
                            "Received malformed or 0-length packet.\n");
                }
            }
        }
        catch (IOException e)
        {
            // debug
            System.err.println(ModbusRTUTCPTransport.logId
                    + "Error while reading from socket: " + e);

            // clean the input stream
            try
            {
                while (this.inputStream.read() != -1);
            }
            catch (IOException e1)
            {
                // debug
                System.err.println(ModbusRTUTCPTransport.logId
                        + "Error while emptying input buffer from socket: "
                        + e);
            }

            // wrap and re-throw
            throw new ModbusIOException(
                    "I/O exception - failed to read.\n" + e);
        }

        // reset the timeout timer
        this.readTimeoutTimer.cancel();

        // return the response read from the socket stream
        return response;

    }// readResponse

    private LengthOffset computePacketLength(int functionCode)
            throws IOException
    {
        // packet length by function code:
        int length = 0;
        int offset = 0;

        switch (functionCode)
        {
            case 0x01:
            case 0x02:
            case 0x03:
            case 0x04:
            case 0x0C:
            case 0x11: // report slave ID version and run/stop state
            case 0x14: // read log entry (60000 memory reference)
            case 0x15: // write log entry (60000 memory reference)
            case 0x17:
            {
                // get a reference to the inner byte buffer
                byte inBuffer[] = this.inputBuffer.getBuffer();
                this.inputStream.read(inBuffer, 2, 1);
                int dataLength = this.inputBuffer.readUnsignedByte();
                length = dataLength + 5; // UID+FC+CRC(2bytes)
                offset = 1; // the size of the data length field
                break;
            }
            case 0x05:
            case 0x06:
            case 0x0B:
            case 0x0F:
            case 0x10:
            case 0x16:
            {
                // read status: only the CRC remains after address and
                // function code
                length = 8;
                break;
            }
            case 0x07:
            case 0x08:
            {
                length = 3;
                break;
            }
            case 0x18:
            {
                // get a reference to the inner byte buffer
                byte inBuffer[] = this.inputBuffer.getBuffer();
                this.inputStream.read(inBuffer, 2, 2);
                length = this.inputBuffer.readUnsignedShort() + 6;// UID+FC+CRC(2bytes)
                offset = 2; // the size of the data length field
                break;
            }
            case 0x83:
            {
                // error code
                length = 5;
                break;
            }
        }

        return new LengthOffset(length, offset);
    }

    @Override
    public void close() throws IOException
    {
        inputStream.close();
        outputStream.close();
    }// close

    /**
     * A private inner class to hold the tuple packet length / packet offset.
     * 
     * @author bonino
     *
     */
    private class LengthOffset
    {
        private int length;
        private int offset;

        /**
         * @param lenght
         * @param offset
         */
        public LengthOffset(int lenght, int offset)
        {
            this.length = lenght;
            this.offset = offset;
        }

        /**
         * @return the lenght
         */
        public int getLength()
        {
            return length;
        }

        /**
         * @return the offset
         */
        public int getOffset()
        {
            return offset;
        }

    }
}
