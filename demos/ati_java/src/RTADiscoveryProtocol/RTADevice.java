/*
 * RTADevice.java
 *
 * Created June 2012
 *
 */

package RTADiscoveryProtocol;

import java.net.*;

/**
 *
 * @author fleda
 * Ported from RTADiscoveryProtocolClient.cs
 */
public class RTADevice {
    /// <summary>
    /// The IP address of the device.
    /// </summary>
    public InetAddress m_ipa;

    /// <summary>
    /// String representation of mac address
    /// </summary>
    public String m_macstring;

    /// <summary>
    /// The device's default gateway.
    /// </summary>
    public InetAddress m_ipaGateway;

    /// <summary>
    /// The network mask of the device.
    /// </summary>
    public InetAddress m_ipaNetmask;

    /// <summary>
    /// The application description string of the device, i.e. what it is, its name.
    /// </summary>
    public String m_strApplication;

    /// <summary>
    /// Gets a description of this device.
    /// </summary>
    /// <returns>A user-friendly description of the device.</returns>
    @Override
    public String toString()
    {
        //Convert IP Address to good looking string. toString() works but puts a "/" in front of the ip address
        byte tempByteArray[] = m_ipa.getAddress();
        int tempIntArray[] = new int[4];
        for(int i = 0; i < 4; i++) tempIntArray[i] = (tempByteArray[i] < 0)?(tempByteArray[i] + 256):(tempByteArray[i]);
        String tempString = new String();
        for(int i = 0; i<4; i++){
            if(i < 3)tempString += String.format("%d.", tempIntArray[i]);
            else tempString += String.format("%d", tempIntArray[i]);
        }
        return String.format("IP=%1$-17sMAC=%2$-21sINFO=%3$s", tempString, m_macstring, m_strApplication);
    }

    /// <summary>
    /// Compares two RTADevices for equality.
    /// </summary>
    /// <param name="obj">The RTADevice to compare against.</param>
    /// <returns>True if obj has the same property values as this.</returns>
    public boolean Equals(Object obj)
    {
        RTADevice comparee = (RTADevice)obj;
        return (comparee.m_ipa.equals(m_ipa) && comparee.m_ipaGateway.equals(m_ipaGateway) &&
            comparee.m_ipaNetmask.equals(m_ipaNetmask) && comparee.m_macstring.equals(m_macstring) &&
            comparee.m_strApplication.equals(m_strApplication));
    }
}