/**
 * 
 */
package net.wimpi.modbus.io;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.ModbusSlaveException;
import net.wimpi.modbus.msg.ExceptionResponse;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.net.RTUTCPMasterConnection;
import net.wimpi.modbus.util.AtomicCounter;
import net.wimpi.modbus.util.Mutex;

/**
 * @author bonino
 * 
 */
public class ModbusRTUTCPTransaction implements ModbusTransaction
{

    // class attributes
    private static AtomicCounter c_TransactionID = new AtomicCounter(
            Modbus.DEFAULT_TRANSACTION_ID);

    // instance attributes and associations
    private RTUTCPMasterConnection m_Connection;
    private ModbusTransport m_IO;
    private ModbusRequest m_Request;
    private ModbusResponse m_Response;
    private boolean m_ValidityCheck = Modbus.DEFAULT_VALIDITYCHECK;
    private boolean m_Reconnecting = Modbus.DEFAULT_RECONNECTING;
    private int m_Retries = Modbus.DEFAULT_RETRIES;
    private int m_TransDelayMS = Modbus.DEFAULT_TRANSMIT_DELAY;

    private Mutex m_TransactionLock = new Mutex();

    /**
     * 
     */
    public ModbusRTUTCPTransaction()
    {
        // TODO Auto-generated constructor stub
    }

    /**
     * Constructs a new <tt>ModbusTCPTransaction</tt> instance with a given
     * <tt>ModbusRequest</tt> to be send when the transaction is executed.
     * <p/>
     * 
     * @param request
     *            a <tt>ModbusRequest</tt> instance.
     */
    public ModbusRTUTCPTransaction(ModbusRequest request)
    {
        setRequest(request);
    }// constructor

    /**
     * Constructs a new <tt>ModbusTCPTransaction</tt> instance with a given
     * <tt>TCPMasterConnection</tt> to be used for transactions.
     * <p/>
     * 
     * @param con
     *            a <tt>TCPMasterConnection</tt> instance.
     */
    public ModbusRTUTCPTransaction(RTUTCPMasterConnection con)
    {
        setConnection(con);
    }// constructor

    /**
     * Sets the connection on which this <tt>ModbusTransaction</tt> should be
     * executed.
     * <p>
     * An implementation should be able to handle open and closed connections.
     * <br>
     * <p/>
     * 
     * @param con
     *            a <tt>TCPMasterConnection</tt>.
     */
    public void setConnection(RTUTCPMasterConnection con)
    {
        m_Connection = con;
        m_IO = con.getModbusTransport();
    }// setConnection

    public void setRequest(ModbusRequest req)
    {
        m_Request = req;
    }// setRequest

    public ModbusRequest getRequest()
    {
        return m_Request;
    }// getRequest

    public ModbusResponse getResponse()
    {
        return m_Response;
    }// getResponse

    public int getTransactionID()
    {
        return c_TransactionID.get();
    }// getTransactionID

    public void setCheckingValidity(boolean b)
    {
        m_ValidityCheck = b;
    }// setCheckingValidity

    public boolean isCheckingValidity()
    {
        return m_ValidityCheck;
    }// isCheckingValidity

    /**
     * Sets the flag that controls whether a connection is openend and closed
     * for <b>each</b> execution or not.
     * <p/>
     * 
     * @param b
     *            true if reconnecting, false otherwise.
     */
    public void setReconnecting(boolean b)
    {
        m_Reconnecting = b;
    }// setReconnecting

    /**
     * Tests if the connection will be openend and closed for <b>each</b>
     * execution.
     * <p/>
     * 
     * @return true if reconnecting, false otherwise.
     */
    public boolean isReconnecting()
    {
        return m_Reconnecting;
    }// isReconnecting

    public int getRetries()
    {
        return m_Retries;
    }// getRetries

    public void setRetries(int num)
    {
        m_Retries = num;
    }// setRetries

    /**
     * Get the TransDelayMS value.
     *
     * @return the TransDelayMS value.
     */
    public int getTransDelayMS()
    {
        return m_TransDelayMS;
    }

    /**
     * Set the TransDelayMS value.
     *
     * @param newTransDelayMS
     *            The new TransDelayMS value.
     */
    public void setTransDelayMS(int newTransDelayMS)
    {
        this.m_TransDelayMS = newTransDelayMS;
    }

    public void execute()
            throws ModbusIOException, ModbusSlaveException, ModbusException
    {

        // 1. check that the transaction can be executed
        assertExecutable();

        try
        {
            // 2. Lock transaction
            /**
             * Note: The way this explicit synchronization is implemented at the
             * moment, there is no ordering of pending threads. The Mutex will
             * simply call notify() and the JVM will handle the rest.
             */
            m_TransactionLock.acquire();

            // 3. open the connection if not connected
            if (!m_Connection.isConnected())
            {
                try
                {
                    m_Connection.connect();
                    m_IO = m_Connection.getModbusTransport();
                }
                catch (Exception ex)
                {
                    throw new ModbusIOException("Connecting failed.");
                }
            }

            // 4. Retry transaction m_Retries times, in case of
            // I/O Exception problems.
            int retryCounter = 0;

            while (retryCounter < m_Retries)
            {
                try
                {
                    // toggle and set the id
                    m_Request.setTransactionID(c_TransactionID.increment());

                    // 3. write request, and read response
                    m_IO.writeMessage(m_Request);

                    // read response message
                    m_Response = m_IO.readResponse();
                    break;
                }
                catch (ModbusIOException ex)
                {
                    if (retryCounter == m_Retries - 1)
                    {
                        throw new ModbusIOException(
                                "Executing transaction failed (tried "
                                        + m_Retries + " times)");
                    }
                    else
                    {
                        retryCounter++;
                        // insert delay between subsequent attempt to read data
                        if (m_TransDelayMS > 0)
                        {
                            try
                            {
                                Thread.sleep(m_TransDelayMS);
                            }
                            catch (InterruptedException ie)
                            {
                                System.err.println("InterruptedException: "
                                        + ie.getMessage());
                            }
                        }
                        continue;
                    }
                }
            }

            // 5. deal with "application level" exceptions
            if (m_Response instanceof ExceptionResponse)
            {
                throw new ModbusSlaveException(
                        ((ExceptionResponse) m_Response).getExceptionCode());
            }

            // 6. close connection if reconnecting
            if (isReconnecting())
            {
                m_Connection.close();
            }

            // 7. Check transaction validity
            if (isCheckingValidity())
            {
                checkValidity();
            }

        }
        catch (InterruptedException ex)
        {
            throw new ModbusIOException(
                    "Thread acquiring lock was interrupted.");
        }
        finally
        {
            m_TransactionLock.release();
        }
    }// execute

    /**
     * Asserts if this <tt>ModbusTCPTransaction</tt> is executable.
     * 
     * @throws ModbusException
     *             if the transaction cannot be asserted as executable.
     */
    private void assertExecutable() throws ModbusException
    {
        if (m_Request == null || m_Connection == null)
        {
            throw new ModbusException(
                    "Assertion failed, transaction not executable");
        }
    }// assertExecuteable

    /**
     * Checks the validity of the transaction, by checking if the values of the
     * response correspond to the values of the request. Use an override to
     * provide some checks, this method will only return.
     * 
     * @throws ModbusException
     *             if this transaction has not been valid.
     */
    protected void checkValidity() throws ModbusException
    {
    }// checkValidity

}
