/**  usage:
 *        TcpMapClient server [ port ]
 *
 *  Server is the IP address or hostname of the remote server
 *  and port is its port number. The second argument is
 *  optional. If omitted, it defaults to 6789.
 *
 *  This program opens a connection to the server, then prompts
 *  for input lines. Each line is sent to the server, if formatted correctly,
 *  adds removes or gets values
 *  from a HashMap maintained on the server. When the user types a blank line,
 *  the connection is closed and the program exits.
 */
import java.io.*;
import java.net.*;

public class DhtClient {
    public static void main(String args[]) throws Exception {
        // get server address using first command-line argument
        InetAddress IP = InetAddress.getByName(args[0]);
        String fileName = null;
        String argType = null;
        String key = null;
        String val = null;
        int port = 60434;
        if(args.length >= 3){
            fileName = args[1];
            argType = args[2];
            key = (args.length >= 4) ? args[3] : null;
            val = (args.length >= 5) ? args[4] : null;
        }

        // open datagram socket
        DatagramSocket sock = new DatagramSocket();

        Packet pkt = new Packet();
        pkt.key = key;
        pkt.val = val;
        pkt.type = argType;
        System.out.println(IP);
        InetSocketAddress adr = new InetSocketAddress(IP, port);
        pkt.send(sock, adr, true);

        pkt.receive(sock, true);

        System.out.println("packet received:");
        System.out.println(pkt.toString());
        sock.close();
    }
}
