/** Server for simple distributed hash table that stores (key,value) strings.
 *  
 *  usage: DhtServer myIp numRoutes cfgFile [ cache ] [ debug ] [ predFile ]
 *  
 *  myIp	is the IP address to use for this server's socket
 *  numRoutes	is the max number of nodes allowed in the DHT's routing table;
 *  		typically lg(numNodes)
 *  cfgFile	is the name of a file in which the server writes the IP
 *		address and port number of its socket
 *  cache	is an optional argument; if present it is the literal string
 *		"cache"; when cache is present, the caching feature of the
 *		server is enabled; otherwise it is not
 *  debug	is an optional argument; if present it is the literal string
 *		"debug"; when debug is present, a copy of every packet received
 *		and sent is printed on stdout
 *  predFile	is an optional argument specifying the configuration file of
 *		this node's predecessor in the DHT; this file is used to obtain
 *		the IP address and port number of the precessor's socket,
 *		allowing this node to join the DHT by contacting predecessor
 *  
 *  The DHT uses UDP packets containing ASCII text. Here's an example of the
 *  UDP payload for a get request from a client.
 *  
 *  CSE473 DHTPv0.1
 *  type:get
 *  key:dungeons
 *  tag:12345
 *  ttl:100
 *  
 *  The first line is just an identifying string that is required in every
 *  DHT packet. The remaining lines all start with a keyword and :, usually
 *  followed by some additional text. Here, the type field specifies that
 *  this is a get request; the key field specifies the key to be looked up;
 *  the tag is a client-specified tag that is returned in the response; and
 *  can be used by the client to match responses with requests; the ttl is
 *  decremented by every DhtServer and if <0, causes the packet to be discarded.
 *  
 *  Possible responses to the above request include:
 *  
 *  CSE473 DHTPv0.1
 *  type:success
 *  key:dungeons
 *  value:dragons
 *  tag:12345
 *  ttl:95
 *  
 *  or
 *  
 *  CSE473 DHTPv0.1
 *  type:no match
 *  key:dungeons
 *  tag:12345
 *  ttl:95
 *  
 *  Put requests are formatted similarly, but in this case the client typically
 *  specifies a value field (omitting the value field causes the pair with the
 *  specified key to be removed).
 *  
 *  The packet type “failure” is used to indicate an error of some sort; in 
 *  this case, the “reason” field provides an explanation of the failure. 
 *  The “join” type is used by a server to join an existing DHT. In the same
 *  way, the “leave” type is used by the leaving server to circle around the 
 *  DHT asking other servers’ to delete it from their routing tables.  The 
 *  “transfer” type is used to transfer (key,value) pairs to a newly added 
 *  server. The “update” type is used to update the predecessor, successor, 
 *  server. The “update” type is used to update the predecessor, successor,
 *  or hash range of another DHT server, usually when a join or leave even
 *  happens. 
 *
 *  Other fields and their use are described briefly below
 *  clientAdr 	is used to specify the IP address and port number of the 
 *              client that sent a particular request; it is added to a request
 *              packet by the first server to receive the request, before 
 *              forwarding the packet to another node in the DHT; an example of
 *              the format is clientAdr:123.45.67.89:51349.
 *  relayAdr  	is used to specify the IP address and port number of the first
 *              server to receive a request packet from the client; it is added
 *              to the packet by the first server before forwarding the packet.
 *  hashRange 	is a pair of integers separated by a colon, specifying a range
 *              of hash indices; it is included in the response to a “join” 
 *              packet, to inform the new DHT server of the set of hash values
 *              it is responsible for; it is also included in the update packet
 *              to update the hash range a server is responsible for.
 *  succInfo  	is the IP address and port number of a server, followed by its
 *              first hash index; this information is included in the response
 *              to a join packet to inform the new DHT server about its 
 *              immediate successor; it’s also included in the update packet 
 *              to change the immediate successor of a DHT server; an example 
 *              of the format is succInfo:123.45.6.7:5678:987654321.
 *  predInfo	is also the IP address and port number of a server, followed
 *              by its first hash index; this information is included in a join
 *              packet to inform the successor DHT server of its new 
 *              predecessor; it is also included in update packets to update 
 *              the new predecessor of a server.
 *  senderInfo	is the IP address and port number of a DHT server, followed by
 *              its first hash index; this information is sent by a DHT to 
 *              provide routing information that can be used by other servers.
 *              It also used in leave packet to let other servers know the IP
 *              address and port number information of the leaving server.
 */

import java.io.*;
import java.net.*;
import java.util.*;
import java.lang.*;
import java.lang.Math;

import sun.misc.Signal;
import sun.misc.SignalHandler;

public class DhtServer {
	private static int numRoutes;	// number of routes in routing table
	private static boolean cacheOn;	// enables caching when true
	private static boolean debug;	// enables debug messages when true

	private static HashMap<String,String> map;	// key/value pairs
	private static HashMap<String,String> cache;	// cached pairs
	private static List<Pair<InetSocketAddress,Integer>> rteTbl;

	private static DatagramSocket sock;
	private static InetSocketAddress myAdr;
	private static InetSocketAddress predecessor; // DHT predecessor
	private static Pair<InetSocketAddress,Integer> myInfo; 
	private static Pair<InetSocketAddress,Integer> predInfo; 
	private static Pair<InetSocketAddress,Integer> succInfo; // successor
	private static Pair<Integer,Integer> hashRange; // my DHT hash range
	private static int sendTag;		// tag for new outgoing packets
	// flag for waiting leave message circle back
	private static boolean stopFlag;
	 
	/** Main method for DHT server.
	 *  Processes command line arguments, initializes data, joins DHT,
	 *  then starts processing requests from clients.
	 */
	public static void main(String[] args) {
		// process command-line arguments
		if (args.length < 3) {
			System.err.println("usage: DhtServer myIp numRoutes " +
					   "cfgFile [debug] [ predFile ] ");
			System.exit(1);
		}
		numRoutes = Integer.parseInt(args[1]);
		String cfgFile = args[2];
		cacheOn = debug = false;
		stopFlag = false;
		String predFile = null;
		for (int i = 3; i < args.length; i++) {
			if (args[i].equals("cache")) cacheOn = true;
			else if (args[i].equals("debug")) debug = true;
			else predFile = args[i];
		}
		
		// open socket for receiving packets
		// write ip and port to config file
		// read predecessor's ip/port from predFile (if there is one)
		InetAddress myIp = null; sock = null; predecessor = null;
		try {	
			myIp = InetAddress.getByName(args[0]);
			sock = new DatagramSocket(0,myIp);
			BufferedWriter cfg =
				new BufferedWriter(
				    new OutputStreamWriter(
					new FileOutputStream(cfgFile),
					"US-ASCII"));
			cfg.write("" +	myIp.getHostAddress() + " " +
					sock.getLocalPort());
			cfg.newLine();
			cfg.close();
			if (predFile != null) {
				BufferedReader pred =
					new BufferedReader(
					    new InputStreamReader(
						new FileInputStream(predFile),
						"US-ASCII"));
				String s = pred.readLine();
				String[] chunks = s.split(" ");
				predecessor = new InetSocketAddress(
					chunks[0],Integer.parseInt(chunks[1]));
			}
		} catch(Exception e) {
			System.err.println("usage: DhtServer myIp numRoutes " +
					   "cfgFile [ cache ] [ debug ] " +
					   "[ predFile ] ");
			System.exit(1);
		}
		myAdr = new InetSocketAddress(myIp,sock.getLocalPort());
		
		// initialize data structures	
		map = new HashMap<>();
		cache = new HashMap<>();
		rteTbl = new LinkedList<>();

		// join the DHT (if not the first node)
		myInfo = null;
		succInfo = null;
		predInfo = null;
		if (predecessor != null) {
			join(predecessor);
		} else {
			myInfo = new Pair<>(myAdr,0);
			succInfo = new Pair<>(myAdr,0);
			predInfo = new Pair<>(myAdr,0);
			hashRange = new Pair<>(0, Integer.MAX_VALUE);
		}


		// start processing requests from clients
		Packet p = new Packet();
		Packet reply = new Packet();
		InetSocketAddress sender = null;
		sendTag = 1;

		/* this function will be called if there's a "TERM" or "INT"
		 * captured by the signal handler. It simply execute the leave
		 * function and leave the program.
		 */ 
		SignalHandler handler = new SignalHandler() {  
		    public void handle(Signal signal) {
		        leave();
		        System.exit(0);
		    }  
		};
		//Signal.handle(new Signal("KILL"), handler); // capture kill -9 signal
		Signal.handle(new Signal("TERM"), handler); // capture kill -15 signal
		Signal.handle(new Signal("INT"), handler); // capture ctrl+c
		
		while (true) {
			try {
				sender = p.receive(sock,debug);
			} catch(Exception e) {
				System.err.println("received packet failure: " + e);
				continue;
			}
			if (sender == null) {
				System.err.println("received packet failure (no sender l.237)");
				continue;
			}
			if (!p.check()) {
				reply.clear();
				reply.type = "failure";
				reply.reason = p.reason;
				reply.tag = p.tag;
				reply.ttl = p.ttl;
				reply.send(sock,sender,debug);
				continue;
			}

			handlePacket(p,sender);
		}
	}

	/** Hash a string, returning a 32 bit integer.
	 *  @param s is a string, typically the key from some get/put operation.
	 *  @return and integer hash value in the interval [0,2^31).
	 */
	public static int hashit(String s) {
		while (s.length() < 16) s += s;
		byte[] sbytes = null;
		try { sbytes = s.getBytes("US-ASCII"); 
		} catch(Exception e) {
			System.err.println("illegal key string");
			System.exit(1);
		}
		int i = 0;
		int h = 0x37ace45d;
		while (i+1 < sbytes.length) {
			int x = (sbytes[i] << 8) | sbytes[i+1];
			h *= x;
			int top = h & 0xffff0000;
			int bot = h & 0xffff;
			h = top | (bot ^ ((top >> 16)&0xffff));
			i += 2;
		}
		if (h < 0) h = -(h+1);
		return h;
	}

	/** Leave an existing DHT.
	 *  
	 *	Send a leave packet to it's successor and wait until stopFlag is 
	 * 	set to "true", which means leave packet has circled back.
	 *
	 *	Send an update packet with the new hashRange and succInfo fields to 
	 *  its predecessor, and send an update packet with the predInfo
	 *  field to its successor. 
	 *	
	 *	Transfers all keys and values to predecessor.  
	 *	Clear all the existing cache, map and rteTbl information
	 */
	public static void leave() {
		//send leave packet to succ and wait for stopFlag
		Packet leavePkt = new Packet();
		leavePkt.type = "leave";
		leavePkt.senderInfo = myInfo;
		leavePkt.send(sock, succInfo.left, debug);
		while(!stopFlag){/*wait*/}
		//send KV pairs to predecessor
		Packet transferPkt = new Packet();
		transferPkt.senderInfo = myInfo;
		transferPkt.type = "transfer";
		//transfer KV pairs
		for (Map.Entry<String, String> entry : map.entrySet()) {
			transferPkt.key = entry.getKey();
			transferPkt.val = entry.getValue();
			System.err.println(transferPkt.key+":"+transferPkt.val);
			transferPkt.send(sock, predInfo.left, debug);
		}
		//Send update pkt with new hashrange and succInfo fields to pred
		Packet updatePkt = new Packet();
		updatePkt.type = "update";
		updatePkt.succInfo = succInfo;
		updatePkt.senderInfo = myInfo;
		updatePkt.succInfo = succInfo;

		//hashrange is from pred's lowest to our highest hashrange
		updatePkt.hashRange = new Pair<>(predInfo.right, hashRange.right);
		updatePkt.send(sock, predInfo.left, debug);

		//change updatePkt and send to successor
		Packet updateSucPkt = new Packet();
		updateSucPkt.predInfo = predInfo;
		updateSucPkt.send(sock, succInfo.left, debug);

		//clear map, cache and rtetbl
		map.clear();
		cache.clear();
		rteTbl.clear();
	}
	
	/** Handle a update packet from a prospective DHT node.
	 *  @param p is the received join packet
	 *  @param adr is the socket address of the host that
	 *  
	 *	The update message might contains infomation need update,
	 *	including predInfo, succInfo, and hashRange. 
	 *  And add the new Predecessor/Successor into the routing table.
	 *	If succInfo is updated, succInfo should be removed from 
	 *	the routing table and the new succInfo should be added
	 *	into the new routing table.
	 */
	public static void handleUpdate(Packet p, InetSocketAddress adr) {
		if (p.predInfo != null){
			predInfo = p.predInfo;
		}
		if (p.succInfo != null){
			succInfo = p.succInfo;
			addRoute(succInfo);
		}
		if (p.hashRange != null){
			hashRange = p.hashRange;
		}
	}

	/** Handle a leave packet from a leaving DHT node.
	*  @param p is the received join packet
	*  @param adr is the socket address of the host that sent the leave packet
	*
	*  If the leave packet is sent by this server, set the stopFlag.
	*  Otherwise firstly send the received leave packet to its successor,
	*  and then remove the routing entry with the senderInfo of the packet.
	*/
	public static void handleLeave(Packet p, InetSocketAddress adr) {
		if (p.senderInfo.equals(myInfo)){
			stopFlag = true;
			return;
		}
		//remove the senderInfo from route table
		removeRoute(p.senderInfo);

		// send the leave message to successor 
		p.send(sock, succInfo.left, debug);
	}
	
	/** Join an existing DHT.
	 *  @param predAdr is the socket address of a server in the DHT,
	 *  
	 *	The join function sends a join packet to an existing server in the
	 *  DHT. Wait for a response packet of type success, add the
	 *  successor to the routing table.
	 */
	public static void join(InetSocketAddress predAdr) {
		//setting pred information
		//left and right nodes are predAddr because we are second node
		myInfo = new Pair<>(myAdr,0);
		succInfo = new Pair<>(predAdr,0);
		predInfo = new Pair<>(predAdr,0);
		Packet p = new Packet();
		p.predInfo = new Pair<>(predAdr,0);
		p.type = "join";
		p.senderInfo = new Pair<>(myAdr, 0);
		p.send(sock, predecessor, debug);
		//Create packet to be filled by predecessor
		Packet joinPkt = new Packet();
		InetSocketAddress sender;
		while (true) {
			//receive packet from predecessor
			try { sender = joinPkt.receive(sock,debug);
			} catch(Exception e) {
				System.err.println("received packet failure" + e);
				System.err.println(e.toString());
				continue;
			}
			//If the sender is null, print an error message
			if (sender == null) {
				System.err.println("received packet failure (sender null)");
				continue;
			}
			//Handle the transfer from the predecessor server
			if(joinPkt.type.equals("transfer")){
				//Sender in this case is actually the predecessor
				handleXfer(p, p.predInfo.left);
			}
			else if(joinPkt.type.equals("success")){
				//If the packet received is of type 'success', update the hashrange,
				//succInfo and predInfo fields
				hashRange = joinPkt.hashRange;
				succInfo = joinPkt.succInfo;
				predInfo = joinPkt.predInfo;
				predecessor = predInfo.left;
				addRoute(succInfo);
				return;
			}
			else{
				System.err.println("Bad packet, no type");
				System.exit(-1);
			}
		}



	}
	
	/** Handle a join packet from a prospective DHT node.
	 *  @param p is the received join packet
	 *  @param succAdr is the socket address of the host that
	 *  sent the join packet (the new successor)
	 *
	 *  Spit hashrange in half, top half to new successor
	 *  Set the new DHT server as my successor, and change my successor's
	 *                 predecessor to the new DHT server
	 *  Send series of transfer packets
	 *                 with KV pairs that fall in the DHT's new hash range
	 *                 with type transfer
	 *
	 */
	public static void handleJoin(Packet p, InetSocketAddress succAdr) {
		//create success packet
		Packet sucPacket = new Packet();
		sucPacket.type = "success";
		sucPacket.predInfo = myInfo;
		sucPacket.succInfo = succInfo;

		//compute the hash range for the new server
		int midHash = 1 + (hashRange.right + hashRange.left)/2;
		if(midHash < 0){ midHash = (-1*midHash)+1;}
		sucPacket.hashRange = new Pair<>(midHash, hashRange.right);

		//change my pred and succ nodes
		InetSocketAddress oldSucc = succInfo.left;
		succInfo = new Pair<>(succAdr, midHash);
		addRoute(succInfo);
		hashRange = new Pair<>(hashRange.left, midHash-1);

		//update successor to have new predecessor (new DHT server)
		Packet updatePkt = new Packet();
		updatePkt.type = "update";
		updatePkt.senderInfo = myInfo;
		updatePkt.predInfo = succInfo;
		updatePkt.send(sock, oldSucc, debug);

		//send transfer packets to new DHT with it's map entries
		int hashval;
		Packet transferPkt = new Packet();
		transferPkt.type = "transfer";
		transferPkt.senderInfo = myInfo;
		for(Map.Entry<String, String> entry :  map.entrySet()){
			hashval = hashit(entry.getKey());
			if(hashval >= midHash){
				transferPkt.key = entry.getKey();
				transferPkt.val = entry.getValue();
				transferPkt.send(sock, succAdr, debug);
			}
		}
		//send success packet
		sucPacket.send(sock, succAdr, debug);

	}
	
	/** Handle a get packet.
	 *  @param p is a get packet
	 *  @param senderAdr is the the socket address of the sender
	 *
	 *  The handleGet function determines whether the packet should be handled
	 *  by current server (either in its cache or map) and if so send a success packet
	 *  or otherwise forward the get packet to the responsible server.
	 */
	public static void handleGet(Packet p, InetSocketAddress senderAdr) {
		//Determine the hash of the key requested and the hashRange the
		//current server is responsible for.
		InetSocketAddress replyAdr;
		int hash = hashit(p.key);
		int left = hashRange.left;
		int right = hashRange.right;
		//check if the requested key's hash is in the range of the current server
		//or contained in its cache
		if ((left <= hash && hash <= right) || cache.containsKey(p.key)) {
			// respond to request using map
			if (p.relayAdr != null) {
				replyAdr = p.relayAdr;
				p.senderInfo = myInfo;
			//If the relay address is null, then we are the relayAdr
			} else {
				replyAdr = senderAdr;
			}
			//send success packet if the current server is responsible for this key
			if (map.containsKey(p.key)) {
				p.type = "success"; p.val = map.get(p.key);
			}
			//send success packet if the current server is responsible for this key
			else if(cache.containsKey(p.key)){
				p.type = "success"; p.val = cache.get(p.key);
			}
			//If the hash is contained in our range, but the map doesn't contain the key
			//Return 'no match'.
			else {
				p.type = "no match"; p.val = null;
			}
			//send packet to the replyAdr
			p.hashRange = hashRange;
			p.send(sock,replyAdr,debug);
		} else {
			// forward around DHT
			if (p.relayAdr == null) {
				p.relayAdr = myAdr; p.clientAdr = senderAdr;
			}
			forward(p,hash);
		}
	}
	
	/** Handle a put packet.
	 *  @param p is a put packet
	 *  @param senderAdr is the the socket address of the sender
	 *  Handle a packet to be added to the map of a server in the DHT
	 *  by determining if it should be handled by the current server (its hash is
	 *  contained within our hash range, or else should be forwarded to the
	 *  proper server).
	 */
	public static void handlePut(Packet p, InetSocketAddress senderAdr) {
		int hash = hashit(p.key);
		//check if this packet is for us
		if(hash >= hashRange.left && hash <= hashRange.right) {
			//If the KV pair should be added to the map of the current server
			//create a success packet to send to the client
			Packet succPkt = new Packet();
			succPkt.type = "success";
			succPkt.senderInfo = myInfo;
			succPkt.key = p.key;
			succPkt.relayAdr = p.relayAdr;
			succPkt.clientAdr = p.clientAdr;
			succPkt.hashRange = hashRange;
			succPkt.senderInfo = myInfo;
			//If the value to be put is null, remove the entry from the map
			if (p.val == null) {
				map.remove(p.key);
				succPkt.val = null;
			} else {
				//If the vlaue is not null, add to the map of the current server
				map.put(p.key, p.val);
				//Update fields of success packet
				succPkt.tag = p.tag;
				succPkt.ttl = p.ttl;
				succPkt.val = p.val;
			}
			//Send success packet to the client
			succPkt.send(sock, succPkt.clientAdr, debug);
		}
		else{
			//If the relayAdr is null, we are the relay server
			if (p.relayAdr == null) {
				p.relayAdr = myAdr; p.clientAdr = senderAdr;
			}
			forward(p, hash);
		}
	}

	/** Handle a transfer packet.
	 *  @param p is a transfer packet
	 *  @param senderAdr is the the address (ip:port) of the sender
	 *  
	 *	transfer KV pairs to the new server
	 */
	public static void handleXfer(Packet p, InetSocketAddress senderAdr) {
		map.put(p.key, p.val);
	}
	
	/** Handle a reply packet.
	 *  @param p is a reply packet, more specifically, a packet of type
	 *  "success", "failure" or "no match"
	 *  @param senderAdr is the the address (ip:port) of the sender
	 *  
	 *  We are the relay server
	 *  Forward packet to client. First, remove clientadr, relayadr, senderinfo
	 *   from packet
	 *  Also, adds shortcut to rtetbl and pkt info to cache
	 */
	public static void handleReply(Packet p, InetSocketAddress senderAdr) {
		//add to rtetbl
		addRoute(new Pair<>(senderAdr, p.hashRange.left));
		//in the case of a get
		if(!(p.key == null) && !(p.val == null) ) {
			//add pkt data to cache
			if(cacheOn){cache.put(p.key, p.val);}
			//remove clientadr, relayadr, senderinfo
			InetSocketAddress cladr = p.clientAdr;
			p.clientAdr = null;
			p.relayAdr = null;
			p.senderInfo = null;
			p.send(sock, cladr, debug);
		}
	}
	
	/** Handle packets received from clients or other servers
	 *  @param p is a packet
	 *  @param senderAdr is the address (ip:port) of the sender
	 */
	public static void handlePacket(Packet p, InetSocketAddress senderAdr) {
		if (p.senderInfo != null & !p.type.equals("leave"))
			addRoute(p.senderInfo);
		if (p.type.equals("get")) {
			handleGet(p,senderAdr);
		} else if (p.type.equals("put")) {
			handlePut(p, senderAdr);
		} else if (p.type.equals("transfer")) {
			handleXfer(p, senderAdr);
		} else if (p.type.equals("success") ||
			   p.type.equals("no match") ||
		     	   p.type.equals("failure")) {
			handleReply(p, senderAdr);
		} else if (p.type.equals("join")) {
			handleJoin(p, senderAdr);
		} else if (p.type.equals("update")){
			handleUpdate(p, senderAdr);
		} else if (p.type.equals("leave")){
			handleLeave(p, senderAdr);
		}
	}
	
	/** Add an entry to the route table.
	 *  @param newRoute is a pair (addr,hash) where addr is the socket
	 *  address for some server and hash is the first hash in that
	 *  server's range
	 *
	 *  If the number of entries in the table exceeds the max
	 *  number allowed, the first entry that does not refer to
	 *  the successor of this server, is removed.
	 *  If debug is true and the set of stored routes does change,
	 *  print the string "rteTbl=" + rteTbl. (IMPORTANT)
	 */
	public static void addRoute(Pair<InetSocketAddress,Integer> newRoute){
		//Only add a route to the table if the table doesn't contain that route
		if(!rteTbl.contains(newRoute)) {
			//Check that the routing table is not already filled to capacity
			if (rteTbl.size() <= numRoutes) {
				//Add newRoute to the retTbl
				if (!newRoute.left.equals(myAdr)) {
					rteTbl.add(newRoute);
				}
			} else {
				//If the capacity of the table has been met, remove the first entry
				//That is not the successor and add the newRoute
				for (int i = 0; i < rteTbl.size(); i++) {
					if (!rteTbl.get(i).equals(succInfo)) {
						rteTbl.remove(i);
						rteTbl.add(newRoute);
						break;
					}
				}
			}
		}
		if(debug){
			System.out.println("rteTbl=" + rteTbl);
		}
	}

	/** Remove an entry from the route tabe.
	 *  @param rmRoute is the route information for some server 
	 *  need to be removed from route table
	 *
	 *  If the route information exists in current entries, remove it.
	 *	Otherwise, do nothing.
	 *  If debug is true and the set of stored routes does change,
	 *  print the string "rteTbl=" + rteTbl. (IMPORTANT)
	 */
	public static void removeRoute(Pair<InetSocketAddress,Integer> rmRoute){
		for(int i = rteTbl.size()-1; i >= 0; i--){
			if(rteTbl.get(i).left.equals(rmRoute.left)){
				rteTbl.remove(i);
			}
		}
		if(debug){
			System.out.println("rteTbl=" + rteTbl);
		}
	}


	/** Forward a packet using the local routing table.
	 *  @param p is a packet to be forwarded
	 *  @param hash is the hash of the packet's key field
	 *
	 *  This method selects a server from its route table that is
	 *  "closest" to the target of this packet (based on hash).
	 *  If firstHash is the first hash in a server's range, then
	 *  we seek to minimize the difference hash-firstHash, where
	 *  the difference is interpreted modulo the range of hash values.
	 *  IMPORTANT POINT - handle "wrap-around" correctly. 
	 *  Once a server is selected, p is sent to that server.
	 */
	public static void forward(Packet p, int hash) {
		if(p.ttl <= 0){
			Packet failure = new Packet();
			failure.type="failure";
			failure.reason="time to live expired";
			failure.send(sock, p.clientAdr, debug);
			return;
		}
		int hashRangelen = Integer.MAX_VALUE;
		Pair<InetSocketAddress,Integer> minNode = new Pair<>(null, null);
		int min = Integer.MAX_VALUE;
		int curHash;
		//Finding the "closest" hash value to the query
		int i = 0;
		for(Pair<InetSocketAddress,Integer> node : rteTbl) {
			curHash = Math.floorMod(hash - node.right, hashRangelen);
			if (curHash <= min) {
				min = curHash;
				minNode = node;
			}
		}
		p.ttl--;
		try {
			p.send(sock, minNode.left, debug);
		}catch (IllegalArgumentException e){
			for(Pair<InetSocketAddress,Integer> node : rteTbl) {
				System.err.println(node.left + ", " + node.right);
			}
			System.err.println(minNode.left);
			System.exit(-1);
		}
	}
}
