/**
 * 
 */
package net.wimpi.modbus.net;

/**
 * A common interface for master connections (not strictly covering serial connections)
 * @author bonino
 *
 */
public interface MasterConnection
{
	
	public void connect() throws Exception;
	
	public void connect(int timeout) throws Exception;
	
	public boolean isConnected();
	
	public void close();
	
}
