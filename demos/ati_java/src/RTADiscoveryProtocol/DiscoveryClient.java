/*
 * RTADevice.java
 *
 * Created June 2012
 *
 */

package RTADiscoveryProtocol;

import java.io.IOException;
import java.net.*;

/**
 *
 * @author fleda
 * Ported from RTADiscoveryProtocolClient.cs
 */
public class DiscoveryClient {
    /// <summary>
    /// The port to which discovery protocol responses are sent.
    /// </summary>
    private static final int RECEIVE_PORT = 28250;

    /// <summary>
    /// The port to which discovery protocol requests are sent.
    /// </summary>
    private static final int SEND_PORT = 51000;

    /// <summary>
    /// The Multicast IP address we use to listen for responses.
    /// </summary>
    private static final String MULTICAST_IP = "224.0.5.128";

    /// <summary>
    /// Delay multiplier sent with discovery request.  This specifies a 10 ms delay
    /// multipler.  Delay multipliers are used to spread out the responses from the
    /// devices and avoid collisions.
    /// </summary>
    private static final byte DELAY_MULTIPLIER = 10;

    /// <summary>
    /// Wait 5 seconds for all devices to respond.
    /// </summary>
    private static final int WAIT_MS = 4000;

    /// <summary>
    /// Beginning of every discovery request message.  Text lets the device know that
    /// this is a discovery request
    /// </summary>
    private static final String DISCOVERY_REQUEST_HEADER = "RTA Device DiscoveryRTAD";

    /// <summary>
    /// Beginning of every RTA response message.
    /// </summary>
    private static final String DISCOVERY_RESPONSE_HEADER = "RTAD";

    /// <summary>
    /// At this time, all IP fields are of length 4.
    /// </summary>
    private static final int IP_FIELD_LENGTH = 4;


    /* The following definitions were taken from the RTA Discovery Protocol Guide. */
    private static final int RTA_DISC_TAG_IP = 2; // IP, same as Digi
    private static final int RTA_DISC_TAG_MAC = 1; // MAC, same as Digi
    private static final int RTA_DISC_TAG_MASK = 3; // Mask, same as Digi
    private static final int RTA_DISC_TAG_GW = 0x0b; // Gateway, same as Digi
    private static final int RTA_DISC_TAG_HW = 0x81; // Hardware platform
    private static final int RTA_DISC_TAG_APP = 0x0d; // Application, same as Digi
    private static final int RTA_DISC_TAG_VER = 8; // Version, same as Digi
    private static final int RTA_DISC_TAG_SEQ = 0x82; // Sequence number
    private static final int RTA_DISC_TAG_CRCS = 0x96; // CRC of selected parts of message
    private static final int RTA_DISC_TAG_CRC = 0xf0; // CRC of entire message
    private static final int RTA_DISC_TAG_TICK = 0x83; // Clock tick when response message sent
    private static final int RTA_DISC_TAG_RND2 = 0x93; // Random number (for encryption)
    private static final int RTA_DISC_TAG_RND1 = 0x84; // Random number (for encryption)
    private static final int RTA_DISC_TAG_RND = 0x94; // Random number (for encryption)
    private static final int RTA_DISC_TAG_PSWD = 0x85; // Encrypted password
    private static final int RTA_DISC_TAG_LOC = 0x86; // Location of the unit
    private static final int RTA_DISC_TAG_DISC = 0x95; // Discovery SW revision
    private static final int RTA_DISC_TAG_MULT = 0xf2; // Response-delay multiplier for broadcast

    
    /**
     * 
     * @return RTADevice[] - array of all RTA Devices discovered.
     * @throws UnknownHostException
     * @throws IOException
     */
    public static RTADevice[] discoverRTADevicesLocalBroadcast()
            throws UnknownHostException, IOException{
        return discoverRTADevicesLocalBroadcast(null);
    }


    /** Broadcasts a discovery request to your local network and listens for responses
     * from RTA devices.  Even devices which aren't configured properly can be found
     * with this method, because it requests the devices to respond to a multicast
     * address, which allows the devices to ignore their default gateway, netmask,
     * etc., and "just send" the response.
     * 
     * @param ipaLocal 
     * @return The list of discovered devices.
     * @throws UnknownHostException
     * @throws IOException
     */
    public static RTADevice[] discoverRTADevicesLocalBroadcast(InetAddress ipaLocal)
            throws UnknownHostException, IOException{
        RTADevice[] rtaList = new RTADevice[0]; //list of RTA Devices
        int i = 0, j = 0;
        ////Multicast Send
        //Set up the multicast address
        InetAddress multicastAddress = InetAddress.getByName(MULTICAST_IP);
        //create multicast socket, used for both sending and receiving
        MulticastSocket socket = new MulticastSocket(RECEIVE_PORT);
        //joing multicast group
        socket.joinGroup(multicastAddress);
        //create discovery request message
        byte discoveryMessage[] = createMulticastDiscoveryRequest();
        //create packet to send discovery message
        DatagramPacket sendPacket = new DatagramPacket(discoveryMessage, 0, discoveryMessage.length,
                InetAddress.getByName("255.255.255.255"), SEND_PORT);
        //set broadcast settings
        socket.setBroadcast(true);
        socket.setSoTimeout(WAIT_MS);
        //send discovery pack1et
        socket.send(sendPacket);
        while(true){
            try{
            //create receive buffer
            byte receiveMessage[] = new byte[205];
            //socket.setSoTimeout(WAIT_MS);

            //create and read in response packet
            DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);

            //SocketTimoutException is being utilized to break out of this loop once it
            //occurs.  It should occur once all devices have been found and no more
            //responses occur.
            //try{
                socket.receive(receivePacket);
            //}
            //catch(SocketTimeoutException ste){
                //do nothing, we have found all devices
             //   break;
            //}
            //Parse received data
            RTADevice tempRTA = parseDiscoveryResponse(receiveMessage);
            //add new device to list of devices
            if(tempRTA != null){
                RTADevice[] tempRTAList = new RTADevice[rtaList.length + 1];
                for(j = 0; j < rtaList.length; j++) tempRTAList[j] = rtaList[j];
                tempRTAList[j] = tempRTA;
                rtaList = tempRTAList;
            }
            }
        catch(SocketTimeoutException ste){
            break;
        }
        catch(Exception e){
            System.out.println("an exception occured!");
            return null;
        }
        }
        //close multicast socket
        socket.close();
        return rtaList;
    }

    /// <summary>
    /// Creates a discovery request message with a multicast reply-to address.
    /// </summary>
    /// <returns>The discovery request message.</returns>
    private static byte[] createMulticastDiscoveryRequest(){
        //array to be returned
        byte discoveryRequest[] = new byte[DISCOVERY_REQUEST_HEADER.length() + 9];
        int i;

        //Place DISCOVERY_REQUEST_HEADER in bytes into discoveryRequest[]
        for(i = 0; i < DISCOVERY_REQUEST_HEADER.length(); i++){
            discoveryRequest[i] = (byte)DISCOVERY_REQUEST_HEADER.charAt(i);
        }

        //add 0x02 and 0x04 to discovery request
        //0x02 indicates that the IP address to respond to
        // follows, and 0x04 indicates that said IP address is 4 bytes long.
        discoveryRequest[DISCOVERY_REQUEST_HEADER.length()] = (byte)2;
        discoveryRequest[DISCOVERY_REQUEST_HEADER.length() + 1] = (byte)4;

        //Add in the four IPV4 numbers to discoveryRequest[]
        String[] ipValues = MULTICAST_IP.split("[.]+");
        int temp;
        for(i = DISCOVERY_REQUEST_HEADER.length()+2; i < (DISCOVERY_REQUEST_HEADER.length() + 6); i++){
            discoveryRequest[i] = (byte)Integer.parseInt(ipValues[(i - 2) - DISCOVERY_REQUEST_HEADER.length()]);
        }
        
        discoveryRequest[i] = (byte)RTA_DISC_TAG_MULT;
        discoveryRequest[i + 1] = 1;//length of delay multiplier
        discoveryRequest[i + 2] = (byte)DELAY_MULTIPLIER;
        
        return discoveryRequest;
    }

    /// <summary>
    /// Parses an RTADevice structure from a discovery response from an RTA device.
    /// </summary>
    /// <param name="abResponse">The response data received from the network.</param>
    /// <returns>The RTADeviceStructure represented by the response, or null
    /// if the response does not contain a valid discovery protocol response.</returns>
    private static RTADevice parseDiscoveryResponse(byte[] abResponse) throws UnknownHostException{
        RTADevice rtad = new RTADevice();//the new RTADevice to be returned
        int i;

        //first check response for correct header
        for(i = 0; i < DISCOVERY_RESPONSE_HEADER.length(); i++){
            if(abResponse[i] != (char)DISCOVERY_RESPONSE_HEADER.charAt(i)) return null;
        }

        //the message had the correct header, ready to start parsing message
        while(i < abResponse.length){
            int j;
            int iFieldLength = abResponse[i + 1];//byte after tag specifier is the field length

            if((i + 2 + iFieldLength) > abResponse.length){
                //the field length specified goes beyond the end of the packet, packet is invalid
                return null;
            }

            switch(abResponse[i]){
                case RTA_DISC_TAG_MAC://Mac address field
                    for(int k = 0; k < iFieldLength; k++){
                        if(k == 0){
                            //generate two characters for mac address fields
                            rtad.m_macstring = String.format("%02x", (abResponse[i + 2 + k]>=0)?
                                (abResponse[i + 2 + k]):(abResponse[i + 2 + k]+256));
                            //note:  I am conditionally adding 256 to the bytes from abResponse because java falsely thinks they
                            //represent negative numbers instead of unsigned positive numbers.  Java does not support
                            //unsigned variables, so I took a roundabout way to accomplish the same task.
                        }
                        else{
                            rtad.m_macstring += "-" + String.format("%02x", (abResponse[i + 2 + k]>=0)?
                                (abResponse[i + 2 + k]):(abResponse[i + 2 + k]+256));
                        }
                    }
                    break;
                case RTA_DISC_TAG_IP://IP Address field
                    //IP fields must be of length 4
                    if(iFieldLength != IP_FIELD_LENGTH) return null;
                    rtad.m_ipa = IPFromSubArray(abResponse, i + 2);
                    break;
                case RTA_DISC_TAG_MASK://Netmask Field
                    //Mask is an IP, so it has length 4
                    if(iFieldLength != IP_FIELD_LENGTH) return null;
                    rtad.m_ipaNetmask = IPFromSubArray(abResponse, i + 2);
                    break;
                case RTA_DISC_TAG_APP: //Application Description field
                    rtad.m_strApplication = "";
                    /* Precondition: iFieldLength = length of description field in
                    * response, abResponse = response from RTA device, i = position
                    * of application description tag in response, rtad.m_strApplication
                    * = "".
                    * Postcondition: rtad.m_strApplication = The application
                    * description from the response, j = length of the description.*/
                    for(j = 0; j < iFieldLength; j++){
                        rtad.m_strApplication += (char)abResponse[i + 2 + j];
                    }
                    break;
                default:
                    break;
            }
            //Move i to next tag
            i += iFieldLength + 2;
        }
        return rtad;
    }

    private static InetAddress IPFromSubArray(byte abPacket[], int iStartPos) throws UnknownHostException{
        byte abSubArray[] = new byte[IP_FIELD_LENGTH];//the subarray containing ip address
        int i;

        /* Precondition: abSubArrray has IP_FIELD_LENGTH slots, abPacket = the response
        * packet, iStartPos = the position of the IP address in the response packet.
        * Postcondition: abSubArray has the elements of the IP address, i ==
        * IP_FIELD_LENGTH.*/
        for(i = 0; i < IP_FIELD_LENGTH; i++){
            abSubArray[i] = abPacket[iStartPos + i];
        }
        //return new InetAddress(abSubArray);
        return InetAddress.getByAddress(abSubArray);
    }
}
