/**
 * 
 */
package net.wimpi.modbus.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.io.ModbusRTUTCPTransport;
import net.wimpi.modbus.io.ModbusTransport;

/**
 * @author bonino
 * 
 */
public class RTUTCPMasterConnection
{
	// the log identifier
	public static String logId = "[RTUTCPMasterConnection]: ";
	
	// the socket upon which sending/receiveing Modbus RTU data
	private Socket socket;
	
	// the timeout for the socket
	private int socketTimeout = Modbus.DEFAULT_TIMEOUT;
	
	// a flag for detecting if the connection is up or not
	private boolean connected;
	
	// the ip address of the remote slave
	private InetAddress slaveIPAddress;
	
	// the port to which connect on the remote slave
	private int slaveIPPort;
	
	// private int retries = Modbus.DEFAULT_RETRIES;
	
	// the RTU over TCP transport
	private ModbusRTUTCPTransport modbusRTUTCPTransport;
	
	/**
	 * Constructs an {@link RTUTCPMasterConnection} instance with a given
	 * destination address and port. It permits to handle Modbus RTU over TCP
	 * connections in a way similar to standard Modbus/TCP connections
	 * 
	 * @param adr
	 *            the destination IP addres as an {@link InetAddress} instance.
	 * @param port
	 *            the port to which connect on the destination address.
	 */
	public RTUTCPMasterConnection(InetAddress adr, int port)
	{
		// store the IP address of the destination
		this.slaveIPAddress = adr;
		
		// store the port of the destination
		this.slaveIPPort = port;
	}
	
	/**
	 * Opens the RTU over TCP connection represented by this object.
	 * 
	 * @throws Exception
	 *             if the connection cannot be open (e.g., due to a network
	 *             failure).
	 */
	public synchronized void connect() throws Exception
	{
		// if not connected, try to connect
		if (!this.connected)
		{
			// handle debug...(TODO: logging?)
			if (Modbus.debug)
				System.out.println(RTUTCPMasterConnection.logId + "connecting...)");
			
			// create a socket towards the remote slave
			this.socket = new Socket(this.slaveIPAddress, this.slaveIPPort);
			
			// set the socket timeout
			setTimeout(this.socketTimeout);
			
			// prepare the RTU over TCP transport to handle communications
			prepareTransport();
			
			// set the connected flag at true
			connected = true;
			
			// handle debug...(TODO: logging?)
			if (Modbus.debug)
				System.out.println(RTUTCPMasterConnection.logId + "successfully connected)");
		}
	}// connect
	
	/**
	 * Closes the RTU over TCP connection represented by this object.
	 */
	public void close()
	{
		// if connected... disconnect, otherwise do nothing
		if (this.connected)
		{
			// try closing the transport...
			try
			{
				this.modbusRTUTCPTransport.close();
			}
			catch (IOException e)
			{
				// handle debug...(TODO: logging?)
				if (Modbus.debug)
					System.out
							.println(RTUTCPMasterConnection.logId + " error while closing the connection, cause:" + e);
			}
			
			// if everything is fine, set the connected flag at false
			connected = false;
		}
	}// close
	
	/**
	 * Returns the <tt>ModbusTransport</tt> associated with this
	 * <tt>TCPMasterConnection</tt>.
	 * 
	 * @return the connection's <tt>ModbusTransport</tt>.
	 */
	public ModbusTransport getModbusTransport()
	{
		return modbusRTUTCPTransport;
	}// getModbusTransport
	
	/**
	 * Prepares the associated <tt>ModbusTransport</tt> of this
	 * <tt>TCPMasterConnection</tt> for use.
	 * 
	 * @throws IOException
	 *             if an I/O related error occurs.
	 */
	private void prepareTransport() throws IOException
	{
		if (modbusRTUTCPTransport == null)
		{
			modbusRTUTCPTransport = new ModbusRTUTCPTransport(socket);
		}
		else
		{
			modbusRTUTCPTransport.setSocket(socket);
		}
	}// prepareIO
	
	/**
	 * Returns the timeout for this <tt>TCPMasterConnection</tt>.
	 * 
	 * @return the timeout as <tt>int</tt>.
	 */
	public int getTimeout()
	{
		return socketTimeout;
	}// getReceiveTimeout
	
	/**
	 * Sets the timeout for this <tt>TCPMasterConnection</tt>.
	 * 
	 * @param timeout
	 *            the timeout as <tt>int</tt>.
	 */
	public void setTimeout(int timeout)
	{
		socketTimeout = timeout;
		if (socket != null)
		{
			try
			{
				socket.setSoTimeout(socketTimeout);
			}
			catch (IOException ex)
			{
				// handle?
			}
		}
	}// setReceiveTimeout
	
	/**
	 * Returns the destination port of this <tt>TCPMasterConnection</tt>.
	 * 
	 * @return the port number as <tt>int</tt>.
	 */
	public int getPort()
	{
		return slaveIPPort;
	}// getPort
	
	/**
	 * Sets the destination port of this <tt>TCPMasterConnection</tt>. The
	 * default is defined as <tt>Modbus.DEFAULT_PORT</tt>.
	 * 
	 * @param port
	 *            the port number as <tt>int</tt>.
	 */
	public void setPort(int port)
	{
		slaveIPPort = port;
	}// setPort
	
	/**
	 * Returns the destination <tt>InetAddress</tt> of this
	 * <tt>TCPMasterConnection</tt>.
	 * 
	 * @return the destination address as <tt>InetAddress</tt>.
	 */
	public InetAddress getAddress()
	{
		return slaveIPAddress;
	}// getAddress
	
	/**
	 * Sets the destination <tt>InetAddress</tt> of this
	 * <tt>TCPMasterConnection</tt>.
	 * 
	 * @param adr
	 *            the destination address as <tt>InetAddress</tt>.
	 */
	public void setAddress(InetAddress adr)
	{
		slaveIPAddress = adr;
	}// setAddress
	
	/**
	 * Tests if this <tt>TCPMasterConnection</tt> is connected.
	 * 
	 * @return <tt>true</tt> if connected, <tt>false</tt> otherwise.
	 */
	public boolean isConnected()
	{
		return connected;
	}// isConnected
	
}
