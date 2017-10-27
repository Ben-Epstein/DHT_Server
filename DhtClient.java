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
        // connect to remote server
        int port = 30123;
        if (args.length > 1) port = Integer.parseInt(args[1]);
        Socket sock = new Socket(args[0], port);

        // create buffered reader & writer for socket's in/out streams
        BufferedReader in = new BufferedReader(new InputStreamReader(
                sock.getInputStream(), "US-ASCII"));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                sock.getOutputStream(), "US-ASCII"));

        // create buffered reader for System.in
        BufferedReader sysin = new BufferedReader(new InputStreamReader(
                System.in));

        String line;
        while (true) {
            line = sysin.readLine();
            if (line == null || line.length() == 0) break;
            // write line on socket and print reply to System.out
            line = line.trim();
            out.write(line);
            out.newLine();
            out.flush();
            System.out.println(in.readLine());
        }
        sock.close();
    }
}
