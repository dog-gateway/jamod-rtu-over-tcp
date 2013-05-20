/**
 * 
 */
package net.wimpi.modbus.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

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
	public void writeMessage(ModbusMessage msg) throws ModbusIOException
	{
		try
		{
			// the data lenght counter
			int bufferLength;
			
			// atomic access to the output buffer
			synchronized (this.outputBuffer)
			{
				// first clear any input from the receive buffer to prepare
				// for the reply since RTU doesn't have message delimiters
				this.clearInput();
				
				// reset the output buffer
				this.outputBuffer.reset();
				
				// prepare the message for "virtual" serial transport
				msg.setHeadless();
				
				// write the message to the output buffer
				msg.writeTo(this.outputBuffer);
				
				// compute the CRC
				int[] crc = ModbusUtil.calculateCRC(this.outputBuffer.getBuffer(), 0, this.outputBuffer.size());
				
				// write the CRC on the output buffer
				this.outputBuffer.writeByte(crc[0]);
				this.outputBuffer.writeByte(crc[1]);
				
				// store the buffer length
				bufferLength = this.outputBuffer.size();
				
				// store the raw output buffer reference
				byte rawBuffer[] = this.outputBuffer.getBuffer();
				
				// write the buffer on the socket
				this.outputStream.write(rawBuffer, 0, bufferLength); // PDU +
																		// CRC
				this.outputStream.flush();
				
				// debug
				if (Modbus.debug)
					System.out.println("Sent: " + ModbusUtil.toHex(rawBuffer, 0, bufferLength));
				
				// store the written buffer as the last request
				this.lastRequest = new byte[bufferLength];
				System.arraycopy(rawBuffer, 0, this.lastRequest, 0, bufferLength);
			}
			
		}
		catch (Exception ex)
		{
			throw new ModbusIOException("I/O failed to write");
		}
		
	}// writeMessage
	
	// This is required for the slave that is not supported
	@Override
	public ModbusRequest readRequest() throws ModbusIOException
	{
		throw new RuntimeException("Operation not supported.");
	} // readRequest
	
	/**
	 * Clear the input if characters are found in the input stream.
	 * 
	 * @throws IOException
	 */
	public void clearInput() throws IOException
	{
		// clean data from the input stream if present
		if (this.inputStream.available() > 0)
		{
			// get the length of the available data
			int len = this.inputStream.available();
			
			// allocate a ra buffer for holding the available data
			byte buf[] = new byte[len];
			
			// consume the data
			this.inputStream.read(buf, 0, len);
			
			// debug
			if (Modbus.debug)
				System.out.println("Clear input: " + ModbusUtil.toHex(buf, 0, len));
		}
	}// cleanInput
	
	@Override
	public ModbusResponse readResponse() throws ModbusIOException
	{
		// the received response
		ModbusResponse response = null;
		
		// the data length
		int dlength = 0;
		
		try
		{
			//atomic access to the input buffer
			synchronized (inputBuffer)
			{
				// read the progressive id
				int packetId = inputBuffer.readUnsignedByte();
				
				// read the function code
				int functionCode = inputBuffer.readUnsignedByte();
				
				// compute the number of bytes composing the message (including the CRC = 2bytes)
				int packetLength = this.computePacketLength(functionCode);
			}
		}
		catch(IOException e)
		{
			//debug
			System.err.println(ModbusRTUTCPTransport.logId+"Error while reading from socket: "+e);
			
			//wrap and re-throw
			throw new ModbusIOException("I/O exception - failed to read.\n"+e.getMessage());
		}
		
		
		// return the response read from the socket stream
		return response;
		
		/*-------------------------- SERIAL IMPLEMENTATION -----------------------------------
		
		try
		{
			do
			{
				// block the input stream
				synchronized (byteInputStream)
				{
					// get the packet uid
					int uid = inputStream.read();
					
					if (Modbus.debug)
						System.out.println(ModbusRTUTCPTransport.logId + "UID: " + uid);
					
					// if the uid is valid (i.e., > 0) continue
					if (uid != -1)
					{
						// get the function code
						int fc = inputStream.read();
						
						if (Modbus.debug)
							System.out.println(ModbusRTUTCPTransport.logId + "Function code: " + uid);
						
						//bufferize the response
						byteOutputStream.reset();
						byteOutputStream.writeByte(uid);
						byteOutputStream.writeByte(fc);
						
						// create the Modbus Response object to acquire length of message
						response = ModbusResponse.createModbusResponse(fc);
						response.setHeadless();
						
						// With Modbus RTU, there is no end frame. Either we
						// assume the message is complete as is or we must do
						// function specific processing to know the correct length. 
						
						//bufferize the response according to the given function code
						getResponse(fc, byteOutputStream);
						
						//compute the response length without considering the CRC
						dlength = byteOutputStream.size() - 2; // less the crc
						
						//debug
						if (Modbus.debug)
							System.out.println("Response: "
									+ ModbusUtil.toHex(byteOutputStream.getBuffer(), 0, dlength + 2));
						
						//TODO: check if needed (restore the buffer state, cursor at 0, same content)
						byteInputStream.reset(inputBuffer, dlength);
						
						// cmopute the buffer CRC
						int[] crc = ModbusUtil.calculateCRC(inputBuffer, 0, dlength); 
						
						// check the CRC against the received one...
						if (ModbusUtil.unsignedByteToInt(inputBuffer[dlength]) != crc[0]
								|| ModbusUtil.unsignedByteToInt(inputBuffer[dlength + 1]) != crc[1])
						{
							throw new IOException("CRC Error in received frame: " + dlength + " bytes: "
									+ ModbusUtil.toHex(byteInputStream.getBuffer(), 0, dlength));
						}
					}
					else
					{
						throw new IOException("Error reading response");
					}
					
					// restore the buffer state, cursor at 0, same content
					byteInputStream.reset(inputBuffer, dlength);
					
					//actually read the response
					if (response != null)
					{
						response.readFrom(byteInputStream);
					}
					
					//flag completion...
					done = true;
					
				}// synchronized
			}
			while (!done);
			return response;
		}
		catch (Exception ex)
		{
			System.err.println("Last request: " + ModbusUtil.toHex(lastRequest));
			System.err.println(ex.getMessage());
			throw new ModbusIOException("I/O exception - failed to read");
		}
		
		------------------------------------------------------------------------------*/
	}// readResponse
	
	private int computePacketLength(int functionCode) throws IOException
	{
		//packet length by function code:
		int length = 0;
		
		switch(functionCode)
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
				length = this.inputBuffer.readUnsignedByte() + 4; //UID+FC+CRC(2bytes)
				break;
			}
			case 0x05:
			case 0x06:
			case 0x0B:
			case 0x0F:
			case 0x10:
			{
				// read status: only the CRC remains after address and
				// function code
				length = 6;
				break;
			}
			case 0x07:
			case 0x08:
			{
				length = 3;
				break;
			}
			case 0x16:
			{
				length = 8;
				break;
			}
			case 0x18:
			{
				length = this.inputStream.readUnsignedShort()+4;//UID+FC+CRC(2bytes)
			}			
		}
		
		return length;
	}

	@Override
	public void close() throws IOException
	{
		inputStream.close();
		outputStream.close();
	}// close
	
	/*
	private void getResponse(int fn, BytesOutputStream out) throws IOException
	{
		int bc = -1, bc2 = -1, bcw = -1;
		int inpBytes = 0;
		byte inpBuf[] = new byte[256];
		
		try
		{
			switch (fn)
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
					// read the byte count;
					bc = inputStream.read();
					out.write(bc);
					// now get the specified number of bytes and the 2 CRC bytes
					setReceiveThreshold(bc + 2);
					inpBytes = inputStream.read(inpBuf, 0, bc + 2);
					out.write(inpBuf, 0, inpBytes);
					m_CommPort.disableReceiveThreshold();
					if (inpBytes != bc + 2)
					{
						System.out.println("Error: looking for " + (bc + 2) + " bytes, received " + inpBytes);
					}
					break;
				case 0x05:
				case 0x06:
				case 0x0B:
				case 0x0F:
				case 0x10:
					// read status: only the CRC remains after address and
					// function code
					setReceiveThreshold(6);
					inpBytes = inputStream.read(inpBuf, 0, 6);
					out.write(inpBuf, 0, inpBytes);
					m_CommPort.disableReceiveThreshold();
					break;
				case 0x07:
				case 0x08:
					// read status: only the CRC remains after address and
					// function code
					setReceiveThreshold(3);
					inpBytes = inputStream.read(inpBuf, 0, 3);
					out.write(inpBuf, 0, inpBytes);
					m_CommPort.disableReceiveThreshold();
					break;
				case 0x16:
					// eight bytes in addition to the address and function codes
					setReceiveThreshold(8);
					inpBytes = inputStream.read(inpBuf, 0, 8);
					out.write(inpBuf, 0, inpBytes);
					m_CommPort.disableReceiveThreshold();
					break;
				case 0x18:
					// read the byte count word
					bc = inputStream.read();
					out.write(bc);
					bc2 = inputStream.read();
					out.write(bc2);
					bcw = ModbusUtil.makeWord(bc, bc2);
					// now get the specified number of bytes and the 2 CRC bytes
					setReceiveThreshold(bcw + 2);
					inpBytes = inputStream.read(inpBuf, 0, bcw + 2);
					out.write(inpBuf, 0, inpBytes);
					m_CommPort.disableReceiveThreshold();
					break;
			}
		}
		catch (IOException e)
		{
			m_CommPort.disableReceiveThreshold();
			throw new IOException("getResponse serial port exception");
		}
	}// getResponse
	*/
	
}
