/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.node.PeerManager.LocationUIDPair;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.TimeSortedHashtable;

/**
 * @author amphibian
 * 
 * Tracks the Location of the node. Negotiates swap attempts.
 * Initiates swap attempts. Deals with locking.
 */
public class LocationManager {

    public class MyCallback extends SendMessageOnErrorCallback {
        
        RecentlyForwardedItem item;

        public MyCallback(Message message, PeerNode pn, RecentlyForwardedItem item) {
            super(message, pn);
            this.item = item;
        }

        public void disconnected() {
            super.disconnected();
            removeRecentlyForwardedItem(item);
        }
        
        public void acknowledged() {
            item.successfullyForwarded = true;
        }
    }
    
    static final int TIMEOUT = 60*1000;
    private static boolean logMINOR;
    final RandomSource r;
    final SwapRequestSender sender;
    SwapRequestInterval interval;
    Node node;
    long timeLastSuccessfullySwapped;
    
    public LocationManager(RandomSource r) {
        loc = Location.randomInitialLocation(r);
        sender = new SwapRequestSender();
        this.r = r;
        recentlyForwardedIDs = new Hashtable();
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }

    Location loc;
    double locChangeSession = 0.0;
    
    int numberOfRemotePeerLocationsSeenInSwaps = 0;

    /**
     * @return The current Location of this node.
     */
    public Location getLocation() {
        return loc;
    }

    /**
     * @param l
     */
    public void setLocation(Location l) {
        this.loc = l;
    }
    
    public void updateLocationChangeSession(double newLoc) {
    	double oldLoc = this.loc.getValue();
    	// Patterned after PeerManager.distance( double, double ), but also need to know the direction of the change
		if (newLoc > oldLoc) {
			double directDifference = newLoc - oldLoc;
			double oppositeDifference = 1.0 - newLoc + oldLoc;
			if (directDifference < oppositeDifference) {
				if(logMINOR) Logger.minor(this, "updateLocationChangeSession: oldLoc: "+oldLoc+" -> newLoc: "+newLoc+" moved: +"+directDifference);
				this.locChangeSession += directDifference;
			} else {
				if(logMINOR) Logger.minor(this, "updateLocationChangeSession: oldLoc: "+oldLoc+" -> newLoc: "+newLoc+" moved: -"+oppositeDifference);
				this.locChangeSession -= oppositeDifference;
			}
		} else {
			double directDifference = oldLoc - newLoc;
			double oppositeDifference = 1.0 - oldLoc + newLoc;
			if (directDifference < oppositeDifference) {
				if(logMINOR) Logger.minor(this, "updateLocationChangeSession: oldLoc: "+oldLoc+" -> newLoc: "+newLoc+" moved: -"+directDifference);
				this.locChangeSession -= directDifference;
			} else {
				if(logMINOR) Logger.minor(this, "updateLocationChangeSession: oldLoc: "+oldLoc+" -> newLoc: "+newLoc+" moved: +"+oppositeDifference);
				this.locChangeSession += oppositeDifference;
			}
		}
    }

    /**
     * Start a thread to send FNPSwapRequests every second when
     * we are not locked.
     */
    public void startSender(Node n, SwapRequestInterval interval) {
        this.node = n;
        this.interval = interval;
        Thread t = new Thread(sender, "SwapRequest sender");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Sends an FNPSwapRequest every second unless the LM is locked
     * (which it will be most of the time)
     */
    public class SwapRequestSender implements Runnable {

        int sendInterval = 2000;
        
        public void run() {
            while(true) {
                try {
                    long startTime = System.currentTimeMillis();
                    double nextRandom = r.nextDouble();
                    while(true) {
                        int sleepTime = interval.getValue();
                        sleepTime *= nextRandom;
                        sleepTime = Math.min(sleepTime, Integer.MAX_VALUE);
                        long endTime = startTime + (int)sleepTime;
                        long now = System.currentTimeMillis();
                        long diff = endTime - now;
                        try {
                            if(diff > 0)
                                Thread.sleep(Math.min((int)diff, 10000));
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                        if(System.currentTimeMillis() >= endTime) break;
                    }
                    // Don't send one if we are locked
                    if(lock()) {
                        if(System.currentTimeMillis() - timeLastSuccessfullySwapped > 30*1000) {
                            try {
                                boolean myFlag = false;
                                double myLoc = loc.getValue();
                                PeerNode[] peers = node.peers.connectedPeers;
                                for(int i=0;i<peers.length;i++) {
                                    PeerNode pn = peers[i];
                                    if(pn.isRoutable()) {
                                        double ploc = pn.getLocation().getValue();
                                        if(Math.abs(ploc - myLoc) <= Double.MIN_VALUE) {
                                            myFlag = true;
                                            // Log an ERROR
                                            // As this is an ERROR, it results from either a bug or malicious action.
                                            // If it happens very frequently, it indicates either an attack or a serious bug.
                                            Logger.error(this, "Randomizing location: my loc="+myLoc+" but loc="+ploc+" for "+pn);
                                            break;
                                        }
                                    }
                                }
                                if(myFlag) {
                                    loc.randomize(node.random);
                                    announceLocChange();
                                    node.writeNodeFile();
                                }
                            } finally {
                                unlock();
                            }
                        } else unlock();
                    } else {
                        continue;
                    }
                    // Check the 
                    startSwapRequest();
                } catch (Throwable t) {
                    Logger.error(this, "Caught "+t, t);
                }
            }
        }
    }
    
    /**
     * Create a new SwapRequest, send it from this node out into
     * the wilderness.
     */
    private void startSwapRequest() {
        Thread t = new Thread(new OutgoingSwapRequestHandler(),
                "Outgoing swap request handler for port "+node.portNumber);
        t.setDaemon(true);
        t.start();
    }
    
    /**
     * Similar to OutgoingSwapRequestHandler, except that we did
     * not initiate the SwapRequest.
     */
    public class IncomingSwapRequestHandler implements Runnable {

        Message origMessage;
        PeerNode pn;
        long uid;
        Long luid;
        RecentlyForwardedItem item;
        
        IncomingSwapRequestHandler(Message msg, PeerNode pn, RecentlyForwardedItem item) {
            this.origMessage = msg;
            this.pn = pn;
            this.item = item;
            uid = origMessage.getLong(DMT.UID);
            luid = new Long(uid);
        }
        
        public void run() {
            MessageDigest md = SHA256.getMessageDigest();
            
            try {
            // We are already locked by caller
            // Because if we can't get lock they need to send a reject
            
            // Firstly, is their message valid?
            
            byte[] hisHash = ((ShortBuffer)origMessage.getObject(DMT.HASH)).getData();
            
            if(hisHash.length != md.getDigestLength()) {
                Logger.error(this, "Invalid SwapRequest from peer: wrong length hash "+hisHash.length+" on "+uid);
                // FIXME: Should we send a reject?
                return;
            }
            
            // Looks okay, lets get on with it
            // Only one ID because we are only receiving
            addForwardedItem(uid, uid, pn, null);
            
            // Create my side
            
            long random = r.nextLong();
            double myLoc = loc.getValue();
            LocationUIDPair[] friendLocsAndUIDs = node.peers.getPeerLocationsAndUIDs();
            double[] friendLocs = extractLocs(friendLocsAndUIDs);
            long[] myValueLong = new long[1+1+friendLocs.length];
            myValueLong[0] = random;
            myValueLong[1] = Double.doubleToLongBits(myLoc);
            for(int i=0;i<friendLocs.length;i++)
                myValueLong[i+2] = Double.doubleToLongBits(friendLocs[i]);
            byte[] myValue = Fields.longsToBytes(myValueLong);
            
            byte[] myHash = md.digest(myValue);
            
            Message m = DMT.createFNPSwapReply(uid, myHash);
            
            MessageFilter filter =
                MessageFilter.create().setType(DMT.FNPSwapCommit).setField(DMT.UID, uid).setTimeout(TIMEOUT).setSource(pn);
            
            node.usm.send(pn, m, null);
            
            Message commit;
            try {
                commit = node.usm.waitFor(filter, null);
            } catch (DisconnectedException e) {
            	if(logMINOR) Logger.minor(this, "Disconnected from "+pn+" while waiting for SwapCommit");
                return;
            }
            
            if(commit == null) {
                // Timed out. Abort
                Logger.error(this, "Timed out waiting for SwapCommit on "+uid+" - this can happen occasionally due to connection closes, if it happens often, there may be a serious problem");
                return;
            }
            
            // We have a SwapCommit
            
            byte[] hisBuf = ((ShortBuffer)commit.getObject(DMT.DATA)).getData();

            if((hisBuf.length % 8 != 0) || (hisBuf.length < 16)) {
                Logger.error(this, "Bad content length in SwapComplete - malicious node? on "+uid);
                return;
            }
            
            // First does it verify?
            
            byte[] rehash = md.digest(hisBuf);
            
            if(!java.util.Arrays.equals(rehash, hisHash)) {
                Logger.error(this, "Bad hash in SwapCommit - malicious node? on "+uid);
                return;
            }
            
            // Now decode it
            
            long[] hisBufLong = Fields.bytesToLongs(hisBuf);
            
            long hisRandom = hisBufLong[0];
            
            double hisLoc = Double.longBitsToDouble(hisBufLong[1]);
            if((hisLoc < 0.0) || (hisLoc > 1.0)) {
                Logger.error(this, "Bad loc: "+hisLoc+" on "+uid);
                return;
            }
            registerKnownLocation(hisLoc);
            
            double[] hisFriendLocs = new double[hisBufLong.length-2];
            for(int i=0;i<hisFriendLocs.length;i++) {
                hisFriendLocs[i] = Double.longBitsToDouble(hisBufLong[i+2]);
                if((hisFriendLocs[i] < 0.0) || (hisFriendLocs[i] > 1.0)) {
                    Logger.error(this, "Bad friend loc: "+hisFriendLocs[i]+" on "+uid);
                    return;
                }
                registerLocationLink(hisLoc, hisFriendLocs[i]);
                registerKnownLocation(hisFriendLocs[i]);
            }
            
            numberOfRemotePeerLocationsSeenInSwaps += hisFriendLocs.length;
            
            // Send our SwapComplete
            
            Message confirm = DMT.createFNPSwapComplete(uid, myValue);
            confirm.addSubMessage(DMT.createFNPSwapLocations(Fields.longsToBytes(extractUIDs(friendLocsAndUIDs))));
            
            node.usm.send(pn, confirm, null);
            
            boolean shouldSwap = shouldSwap(myLoc, friendLocs, hisLoc, hisFriendLocs, random ^ hisRandom);
            
            spyOnLocations(commit, true, shouldSwap, myLoc);
            
            if(shouldSwap) {
                timeLastSuccessfullySwapped = System.currentTimeMillis();
                // Swap
                updateLocationChangeSession(hisLoc);
                loc.setValue(hisLoc);
                if(logMINOR) Logger.minor(this, "Swapped: "+myLoc+" <-> "+hisLoc+" - "+uid);
                swaps++;
                announceLocChange();
                node.writeNodeFile();
            } else {
            	if(logMINOR) Logger.minor(this, "Didn't swap: "+myLoc+" <-> "+hisLoc+" - "+uid);
                noSwaps++;
            }
            SHA256.returnMessageDigest(md);
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
        } finally {
            unlock();
            removeRecentlyForwardedItem(item);
        }
        }

    }
    
    /**
     * Locks the LocationManager.
     * Sends an FNPSwapRequest out into the network.
     * Waits for a reply.
     * Etc.
     */
    public class OutgoingSwapRequestHandler implements Runnable {
        
        RecentlyForwardedItem item;
        
        public void run() {
            long uid = r.nextLong();            
            if(!lock()) return;
            try {
                startedSwaps++;
                // We can't lock friends_locations, so lets just
                // pretend that they're locked
                long random = r.nextLong();
                double myLoc = loc.getValue();
                LocationUIDPair[] friendLocsAndUIDs = node.peers.getPeerLocationsAndUIDs();
                double[] friendLocs = extractLocs(friendLocsAndUIDs);
                long[] myValueLong = new long[1+1+friendLocs.length];
                myValueLong[0] = random;
                myValueLong[1] = Double.doubleToLongBits(myLoc);
                for(int i=0;i<friendLocs.length;i++)
                    myValueLong[i+2] = Double.doubleToLongBits(friendLocs[i]);
                byte[] myValue = Fields.longsToBytes(myValueLong);
                
                byte[] myHash = SHA256.digest(myValue);
                
                Message m = DMT.createFNPSwapRequest(uid, myHash, 6);
                
                PeerNode pn = node.peers.getRandomPeer();
                if(pn == null) {
                    // Nowhere to send
                    return;
                }
                // Only 1 ID because we are sending; we won't receive
                item = addForwardedItem(uid, uid, null, pn);

                if(logMINOR) Logger.minor(this, "Sending SwapRequest "+uid+" to "+pn);
                
                MessageFilter filter1 =
                    MessageFilter.create().setType(DMT.FNPSwapRejected).setField(DMT.UID, uid).setSource(pn);
                MessageFilter filter2 =
                    MessageFilter.create().setType(DMT.FNPSwapReply).setField(DMT.UID, uid).setSource(pn);
                MessageFilter filter = filter1.or(filter2);
                // 60 seconds
                filter.setTimeout(TIMEOUT);
                
                node.usm.send(pn, m, null);
                
                if(logMINOR) Logger.minor(this, "Waiting for SwapReply/SwapRejected on "+uid);
                Message reply;
                try {
                    reply = node.usm.waitFor(filter, null);
                } catch (DisconnectedException e) {
                	if(logMINOR) Logger.minor(this, "Disconnected while waiting for SwapReply/SwapRejected for "+uid);
                    return;
                }

                if(reply == null) {
                    if(pn.isRoutable() && (System.currentTimeMillis() - pn.timeLastConnectionCompleted() > TIMEOUT*2)) {
                        // Timed out! Abort...
                        Logger.error(this, "Timed out waiting for SwapRejected/SwapReply on "+uid);
                    }
                    return;
                }
                
                if(reply.getSpec() == DMT.FNPSwapRejected) {
                    // Failed. Abort.
                	if(logMINOR) Logger.minor(this, "Swap rejected on "+uid);
                    return;
                }
                
                // We have an FNPSwapReply, yay
                // FNPSwapReply is exactly the same format as FNPSwapRequest
                byte[] hisHash = ((ShortBuffer)reply.getObject(DMT.HASH)).getData();
                
                Message confirm = DMT.createFNPSwapCommit(uid, myValue);
                confirm.addSubMessage(DMT.createFNPSwapLocations(Fields.longsToBytes(extractUIDs(friendLocsAndUIDs))));

                filter1.clearOr();
                MessageFilter filter3 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPSwapComplete).setTimeout(TIMEOUT).setSource(pn);
                filter = filter1.or(filter3);
                
                node.usm.send(pn, confirm, null);
                
                if(logMINOR) Logger.minor(this, "Waiting for SwapComplete: uid = "+uid);

                try {
                    reply = node.usm.waitFor(filter, null);
                } catch (DisconnectedException e) {
                	if(logMINOR) Logger.minor(this, "Disconnected waiting for SwapComplete on "+uid);
                    return;
                }
                
                if(reply == null) {
                    if(pn.isRoutable() && (System.currentTimeMillis() - pn.timeLastConnectionCompleted() > TIMEOUT*2)) {
                        // Hrrrm!
                        Logger.error(this, "Timed out waiting for SwapComplete - malicious node?? on "+uid);
                    }
                    return;
                }
                
                if(reply.getSpec() == DMT.FNPSwapRejected) {
                    Logger.error(this, "Got SwapRejected while waiting for SwapComplete. This can happen occasionally because of badly timed disconnects, but if it happens frequently it indicates a bug or an attack");
                    return;
                }
                
                byte[] hisBuf = ((ShortBuffer)reply.getObject(DMT.DATA)).getData();

                if((hisBuf.length % 8 != 0) || (hisBuf.length < 16)) {
                    Logger.error(this, "Bad content length in SwapComplete - malicious node? on "+uid);
                    return;
                }
                
                // First does it verify?
                
                byte[] rehash = SHA256.digest(hisBuf);
                
                if(!java.util.Arrays.equals(rehash, hisHash)) {
                    Logger.error(this, "Bad hash in SwapComplete - malicious node? on "+uid);
                    return;
                }
                
                // Now decode it
                
                long[] hisBufLong = Fields.bytesToLongs(hisBuf);
                
                long hisRandom = hisBufLong[0];
                
                double hisLoc = Double.longBitsToDouble(hisBufLong[1]);
                if((hisLoc < 0.0) || (hisLoc > 1.0)) {
                    Logger.error(this, "Bad loc: "+hisLoc+" on "+uid);
                    return;
                }
                registerKnownLocation(hisLoc);
                
                double[] hisFriendLocs = new double[hisBufLong.length-2];
                for(int i=0;i<hisFriendLocs.length;i++) {
                    hisFriendLocs[i] = Double.longBitsToDouble(hisBufLong[i+2]);
                    if((hisFriendLocs[i] < 0.0) || (hisFriendLocs[i] > 1.0)) {
                        Logger.error(this, "Bad friend loc: "+hisFriendLocs[i]+" on "+uid);
                        return;
                    }
                    registerLocationLink(hisLoc, hisFriendLocs[i]);
                    registerKnownLocation(hisFriendLocs[i]);
                }
                
                numberOfRemotePeerLocationsSeenInSwaps += hisFriendLocs.length;
                
                boolean shouldSwap = shouldSwap(myLoc, friendLocs, hisLoc, hisFriendLocs, random ^ hisRandom);
                
                spyOnLocations(reply, true, shouldSwap, myLoc);
                
                if(shouldSwap) {
                    timeLastSuccessfullySwapped = System.currentTimeMillis();
                    // Swap
                    updateLocationChangeSession(hisLoc);
                    loc.setValue(hisLoc);
                    if(logMINOR) Logger.minor(this, "Swapped: "+myLoc+" <-> "+hisLoc+" - "+uid);
                    swaps++;
                    announceLocChange();
                    node.writeNodeFile();
                } else {
                	if(logMINOR) Logger.minor(this, "Didn't swap: "+myLoc+" <-> "+hisLoc+" - "+uid);
                    noSwaps++;
                }
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t, t);
            } finally {
                unlock();
                if(item != null)
                    removeRecentlyForwardedItem(item);
            }
        }

    }
    
    /**
     * Tell all connected peers that our location has changed
     */
    private void announceLocChange() {
        Message msg = DMT.createFNPLocChangeNotification(loc.getValue());
        node.peers.localBroadcast(msg);
    }
    
    private boolean locked;

    public static int swaps;
    public static int noSwaps;
    public static int startedSwaps;
    public static int swapsRejectedAlreadyLocked;
    public static int swapsRejectedNowhereToGo;
    public static int swapsRejectedRateLimit;
    public static int swapsRejectedLoop;
    public static int swapsRejectedRecognizedID;
    
    long lockedTime;
    
    /**
     * Lock the LocationManager.
     * @return True if we managed to lock the LocationManager,
     * false if it was already locked.
     */
    synchronized boolean lock() {
        if(locked) {
        	if(logMINOR) Logger.minor(this, "Already locked");
        	return false;
        }
        if(logMINOR) Logger.minor(this, "Locking on port "+node.portNumber);
        locked = true;
        lockedTime = System.currentTimeMillis();
        return true;
    }
    
    synchronized void unlock() {
        if(!locked)
            throw new IllegalStateException("Unlocking when not locked!");
        locked = false;
        long lockTime = System.currentTimeMillis() - lockedTime;
        if(logMINOR) {
        	Logger.minor(this, "Unlocking on port "+node.portNumber);
        	Logger.minor(this, "lockTime: "+lockTime);
        }
    }

    /**
     * Should we swap? This method implements the core of the Freenet
     * 0.7 routing algorithm - the criteria for swapping.
     * Oskar says this is derived from the Metropolis-Hastings algorithm.
     * 
     * Anyway:
     * Two nodes choose each other and decide to attempt a switch. They
     * calculate the distance of all their edges currently (that is the
     * distance between their currend ID and that of their neighbors), and
     * multiply up all these values to get A. Then they calculate the
     * distance to all their neighbors as it would be if they switched
     * IDs, and multiply up these values to get B.
     * 
     * If A > B then they switch.
     * 
     * If A <= B, then calculate p = A / B. They then switch with
     * probability p (that is, switch if rand.nextFloat() < p).
     * 
     * @param myLoc My location as a double.
     * @param friendLocs Locations of my friends as doubles.
     * @param hisLoc His location as a double
     * @param hisFriendLocs Locations of his friends as doubles.
     * @param rand Shared random number used to decide whether to swap.
     * @return
     */
    private boolean shouldSwap(double myLoc, double[] friendLocs, double hisLoc, double[] hisFriendLocs, long rand) {
        
        // A = distance from us to all our neighbours, for both nodes,
        // all multiplied together

        // Dump

        StringBuffer sb = new StringBuffer();
        
        sb.append("my: ").append(myLoc).append(", his: ").append(hisLoc).append(", myFriends: ");
        sb.append(friendLocs.length).append(", hisFriends: ").append(hisFriendLocs.length).append(" mine:\n");
        
        for(int i=0;i<friendLocs.length;i++) {
            sb.append(friendLocs[i]);
            sb.append(' ');
        }

        sb.append("\nhis:\n");
        
        for(int i=0;i<hisFriendLocs.length;i++) {
            sb.append(hisFriendLocs[i]);
            sb.append(' ');
        }

        if(logMINOR) Logger.minor(this, sb.toString());
        
        double A = 1.0;
        for(int i=0;i<friendLocs.length;i++) {
            if(Math.abs(friendLocs[i] - myLoc) <= Double.MIN_VALUE) continue;
            A *= PeerManager.distance(friendLocs[i], myLoc);
        }
        for(int i=0;i<hisFriendLocs.length;i++) {
            if(Math.abs(hisFriendLocs[i] - hisLoc) <= Double.MIN_VALUE) continue;
            A *= PeerManager.distance(hisFriendLocs[i], hisLoc);
        }
        
        // B = the same, with our two values swapped
        double B = 1.0;
        for(int i=0;i<friendLocs.length;i++) {
            if(Math.abs(friendLocs[i] - hisLoc) <= Double.MIN_VALUE) continue;
            B *= PeerManager.distance(friendLocs[i], hisLoc);
        }
        for(int i=0;i<hisFriendLocs.length;i++) {
            if(Math.abs(hisFriendLocs[i] - myLoc) <= Double.MIN_VALUE) continue;
            B *= PeerManager.distance(hisFriendLocs[i], myLoc);
        }
        
        //Logger.normal(this, "A="+A+" B="+B);
        
        if(A>B) return true;
        
        double p = A / B;
        
        // Take last 63 bits, then turn into a double
        double randProb = ((double)(rand & Long.MAX_VALUE)) 
                / ((double) Long.MAX_VALUE);
        
        //Logger.normal(this, "p="+p+" randProb="+randProb);
        
        if(randProb < p) return true;
        return false;
    }

    static final double SWAP_ACCEPT_PROB = 0.25;
    
    final Hashtable recentlyForwardedIDs;
    
    static class RecentlyForwardedItem {
        final long incomingID; // unnecessary?
        final long outgoingID;
        final long addedTime;
        long lastMessageTime; // can delete when no messages for 2*TIMEOUT
        final PeerNode requestSender;
        PeerNode routedTo;
        // Set when a request is accepted. Unset when we send one.
        boolean successfullyForwarded;
        
        RecentlyForwardedItem(long id, long outgoingID, PeerNode from, PeerNode to) {
            this.incomingID = id;
            this.outgoingID = outgoingID;
            requestSender = from;
            routedTo = to;
            addedTime = System.currentTimeMillis();
            lastMessageTime = addedTime;
        }
    }
    
    /**
     * Handle an incoming SwapRequest
     * @return True if we have handled the message, false if it needs
     * to be handled otherwise.
     */
    public boolean handleSwapRequest(Message m) {
        PeerNode pn = (PeerNode)m.getSource();
        long uid = m.getLong(DMT.UID);
        Long luid = new Long(uid);
        long oid = uid+1;
        // We have two separate IDs so we can deal with two visits
        // separately. This is because we want it to be as random 
        // as possible.
        // This means we can and should check for the same ID being
        // sent twice.
        RecentlyForwardedItem item = (RecentlyForwardedItem) recentlyForwardedIDs.get(luid);
        if(item != null) {
        	if(logMINOR) Logger.minor(this, "Rejecting - same ID as previous request");
            // Reject
            Message reject = DMT.createFNPSwapRejected(uid);
            try {
                pn.sendAsync(reject, null, 0, null);
            } catch (NotConnectedException e) {
            	if(logMINOR) Logger.minor(this, "Lost connection to "+pn+" rejecting SwapRequest");
            }
            swapsRejectedRecognizedID++;
            return true;
        }
        if(pn.shouldRejectSwapRequest()) {
        	if(logMINOR) Logger.minor(this, "Advised to reject SwapRequest by PeerNode - rate limit");
            // Reject
            Message reject = DMT.createFNPSwapRejected(uid);
            try {
                pn.sendAsync(reject, null, 0, null);
            } catch (NotConnectedException e) {
            	if(logMINOR) Logger.minor(this, "Lost connection rejecting SwapRequest from "+pn);
            }
            swapsRejectedRateLimit++;
            return true;
        }
        if(logMINOR) Logger.minor(this, "SwapRequest from "+pn+" - uid="+uid);
        int htl = m.getInt(DMT.HTL)-1;
        // Either forward it or handle it
        if(htl == 0) {
        	if(logMINOR) Logger.minor(this, "Accepting?... "+uid);
            // Accept - handle locally
            if(!lock()) {
            	if(logMINOR) Logger.minor(this, "Can't obtain lock on "+uid+" - rejecting to "+pn);
                // Reject
                Message reject = DMT.createFNPSwapRejected(uid);
                try {
                    pn.sendAsync(reject, null, 0, null);
                } catch (NotConnectedException e1) {
                	if(logMINOR) Logger.minor(this, "Lost connection rejecting SwapRequest (locked) from "+pn);
                }
                swapsRejectedAlreadyLocked++;
                return true;
            }
            try {
                item = addForwardedItem(uid, oid, pn, null);
                // Locked, do it
                IncomingSwapRequestHandler isrh =
                    new IncomingSwapRequestHandler(m, pn, item);
                if(logMINOR) Logger.minor(this, "Handling... "+uid);
                Thread t = new Thread(isrh, "Incoming swap request handler for port "+node.portNumber);
                t.setDaemon(true);
                t.start();
                return true;
            } catch (Error e) {
                unlock();
                throw e;
            } catch (RuntimeException e) {
                unlock();
                throw e;
            }
        } else {
            m.set(DMT.HTL, htl);
            m.set(DMT.UID, oid);
            if(logMINOR) Logger.minor(this, "Forwarding... "+uid);
            while(true) {
                // Forward
                PeerNode randomPeer = node.peers.getRandomPeer(pn);
                if(randomPeer == null) {
                	if(logMINOR) Logger.minor(this, "Late reject "+uid);
                    Message reject = DMT.createFNPSwapRejected(uid);
                    try {
                        pn.sendAsync(reject, null, 0, null);
                    } catch (NotConnectedException e1) {
                        Logger.normal(this, "Late reject but disconnected from sender: "+pn);
                    }
                    swapsRejectedNowhereToGo++;
                    return true;
                }
                if(logMINOR) Logger.minor(this, "Forwarding "+uid+" to "+randomPeer);
                item = addForwardedItem(uid, oid, pn, randomPeer);
                item.successfullyForwarded = false;
                try {
                    // Forward the request.
                    // Note that we MUST NOT send this blocking as we are on the
                    // receiver thread.
                    randomPeer.sendAsync(m, new MyCallback(DMT.createFNPSwapRejected(uid), pn, item), 0, null);
                } catch (NotConnectedException e) {
                    // Try a different node
                    continue;
                }
                return true;
            }
        }
    }

    private RecentlyForwardedItem addForwardedItem(long uid, long oid, PeerNode pn, PeerNode randomPeer) {
        RecentlyForwardedItem item = new RecentlyForwardedItem(uid, oid, pn, randomPeer);
        recentlyForwardedIDs.put(new Long(uid), item);
        recentlyForwardedIDs.put(new Long(oid), item);
        return item;
    }

    /**
     * Handle an unmatched FNPSwapReply
     * @return True if we recognized and forwarded this reply.
     */
    public boolean handleSwapReply(Message m) {
        long uid = m.getLong(DMT.UID);
        Long luid = new Long(uid);
        RecentlyForwardedItem item = (RecentlyForwardedItem) recentlyForwardedIDs.get(luid);
        if(item == null) {
            Logger.error(this, "Unrecognized SwapReply: ID "+uid);
            return false;
        }
        if(item.requestSender == null) {
        	if(logMINOR) Logger.minor(this, "SwapReply from "+m.getSource()+" on chain originated locally "+uid);
            return false;
        }
        if(item.routedTo == null) {
            Logger.error(this, "Got SwapReply on "+uid+" but routedTo is null!");
            return false;
        }
        if(m.getSource() != item.routedTo) {
            Logger.error(this, "Unmatched swapreply "+uid+" from wrong source: From "+m.getSource()+
                    " should be "+item.routedTo+" to "+item.requestSender);
            return true;
        }
        item.lastMessageTime = System.currentTimeMillis();
        // Returning to source - use incomingID
        m.set(DMT.UID, item.incomingID);
        if(logMINOR) Logger.minor(this, "Forwarding SwapReply "+uid+" from "+m.getSource()+" to "+item.requestSender);
        try {
            item.requestSender.sendAsync(m, null, 0, null);
        } catch (NotConnectedException e) {
        	if(logMINOR) Logger.minor(this, "Lost connection forwarding SwapReply "+uid+" to "+item.requestSender);
        }
        return true;
    }
    
    /**
     * Handle an unmatched FNPSwapRejected
     * @return True if we recognized and forwarded this message.
     */
    public boolean handleSwapRejected(Message m) {
        long uid = m.getLong(DMT.UID);
        Long luid = new Long(uid);
        RecentlyForwardedItem item = (RecentlyForwardedItem) recentlyForwardedIDs.get(luid);
        if(item == null) return false;
        if(item.requestSender == null){
        	if(logMINOR) Logger.minor(this, "Got a FNPSwapRejected without any requestSender set! we can't and won't claim it! UID="+uid);
        	return false;
        }
        if(item.routedTo == null) {
            Logger.error(this, "Got SwapRejected on "+uid+" but routedTo is null!");
            return false;
        }
        if(m.getSource() != item.routedTo) {
            Logger.error(this, "Unmatched swapreply "+uid+" from wrong source: From "+m.getSource()+
                    " should be "+item.routedTo+" to "+item.requestSender);
            return true;
        }
        removeRecentlyForwardedItem(item);
        item.lastMessageTime = System.currentTimeMillis();
        if(logMINOR) Logger.minor(this, "Forwarding SwapRejected "+uid+" from "+m.getSource()+" to "+item.requestSender);
        // Returning to source - use incomingID
        m.set(DMT.UID, item.incomingID);
        try {
            item.requestSender.sendAsync(m, null, 0, null);
        } catch (NotConnectedException e) {
        	if(logMINOR) Logger.minor(this, "Lost connection forwarding SwapRejected "+uid+" to "+item.requestSender);
        }
        return true;
    }
    
    /**
     * Handle an unmatched FNPSwapCommit
     * @return True if we recognized and forwarded this message.
     */
    public boolean handleSwapCommit(Message m) {
        long uid = m.getLong(DMT.UID);
        Long luid = new Long(uid);
        RecentlyForwardedItem item = (RecentlyForwardedItem) recentlyForwardedIDs.get(luid);
        if(item == null) return false;
        if(item.routedTo == null) return false;
        if(m.getSource() != item.requestSender) {
            Logger.error(this, "Unmatched swapreply "+uid+" from wrong source: From "+m.getSource()+
                    " should be "+item.requestSender+" to "+item.routedTo);
            return true;
        }
        item.lastMessageTime = System.currentTimeMillis();
        if(logMINOR) Logger.minor(this, "Forwarding SwapCommit "+uid+ ',' +item.outgoingID+" from "+m.getSource()+" to "+item.routedTo);
        // Sending onwards - use outgoing ID
        m.set(DMT.UID, item.outgoingID);
        try {
            item.routedTo.sendAsync(m, new SendMessageOnErrorCallback(DMT.createFNPSwapRejected(item.incomingID), item.requestSender), 0, null);
        } catch (NotConnectedException e) {
        	if(logMINOR) Logger.minor(this, "Lost connection forwarding SwapCommit "+uid+" to "+item.routedTo);
        }
        spyOnLocations(m, false);
        return true;
    }

	/**
     * Handle an unmatched FNPSwapComplete
     * @return True if we recognized and forwarded this message.
     */
    public boolean handleSwapComplete(Message m) {
        long uid = m.getLong(DMT.UID);
        if(logMINOR) Logger.minor(this, "handleSwapComplete("+uid+ ')');
        Long luid = new Long(uid);
        RecentlyForwardedItem item = (RecentlyForwardedItem) recentlyForwardedIDs.get(luid);
        if(item == null) {
        	if(logMINOR) Logger.minor(this, "Item not found: "+uid+": "+m);
            return false;
        }
        if(item.requestSender == null) {
        	if(logMINOR) Logger.minor(this, "Not matched "+uid+": "+m);
            return false;
        }
        if(item.routedTo == null) {
            Logger.error(this, "Got SwapComplete on "+uid+" but routedTo == null! (meaning we accepted it, presumably)");
            return false;
        }
        if(m.getSource() != item.routedTo) {
            Logger.error(this, "Unmatched swapreply "+uid+" from wrong source: From "+m.getSource()+
                    " should be "+item.routedTo+" to "+item.requestSender);
            return true;
        }
        if(logMINOR) Logger.minor(this, "Forwarding SwapComplete "+uid+" from "+m.getSource()+" to "+item.requestSender);
        // Returning to source - use incomingID
        m.set(DMT.UID, item.incomingID);
        try {
            item.requestSender.sendAsync(m, null, 0, null);
        } catch (NotConnectedException e) {
            Logger.normal(this, "Lost connection forwarding SwapComplete "+uid+" to "+item.requestSender);
        }
        item.lastMessageTime = System.currentTimeMillis();
        removeRecentlyForwardedItem(item);
        spyOnLocations(m, false);
        return true;
    }

    private void spyOnLocations(Message m, boolean ignoreIfOld) {
    	spyOnLocations(m, ignoreIfOld, false, -1.0);
    }
    
    /** Spy on locations in somebody else's swap request. Greatly increases the
     * speed at which we can gather location data to estimate the network's size.
     * @param swappingWithMe 
     */
    private void spyOnLocations(Message m, boolean ignoreIfOld, boolean swappingWithMe, double myLoc) {
    	
    	long[] uids = null;
    	
    	Message uidsMessage = m.getSubMessage(DMT.FNPSwapNodeUIDs);
    	if(uidsMessage != null) {
    		uids = Fields.bytesToLongs(((ShortBuffer) uidsMessage.getObject(DMT.NODE_UIDS)).getData());
    	}
    	
        byte[] data = ((ShortBuffer)m.getObject(DMT.DATA)).getData();
        
        if(data.length < 16 || data.length % 8 != 0) {
        	Logger.error(this, "Data invalid length in swap commit: "+data.length, new Exception("error"));
        	return;
        }
        
        double[] locations = Fields.bytesToDoubles(data, 8, data.length-8);
        
        double hisLoc = locations[0];
        if(hisLoc < 0.0 || hisLoc > 1.0) {
        	Logger.error(this, "Invalid hisLoc in swap commit: "+hisLoc, new Exception("error"));
        	return;
        }
        
        if(uids != null) {
        	registerKnownLocation(hisLoc, uids[0]);
        	if(swappingWithMe)
        		registerKnownLocation(myLoc, uids[0]);
        } else if (!ignoreIfOld)
        	registerKnownLocation(hisLoc);
        
        for(int i=1;i<locations.length;i++) {
        	double loc = locations[i];
        	if(uids != null) {
        		registerKnownLocation(loc, uids[i-1]);
        		registerLink(uids[0], uids[i-1]);
        	} else if(!ignoreIfOld) {
        		registerKnownLocation(loc);
        		registerLocationLink(hisLoc, loc);
        	}
        }
        
	}

    public void clearOldSwapChains() {
        long now = System.currentTimeMillis();
        synchronized(recentlyForwardedIDs) {
            RecentlyForwardedItem[] items = new RecentlyForwardedItem[recentlyForwardedIDs.size()];
            items = (RecentlyForwardedItem[]) recentlyForwardedIDs.values().toArray(items);
            for(int i=0;i<items.length;i++) {
                if(now - items[i].lastMessageTime > (TIMEOUT*2)) {
                    removeRecentlyForwardedItem(items[i]);
                }
            }
        }
    }

    /**
     * We lost the connection to a node, or it was restarted.
     */
    public void lostOrRestartedNode(PeerNode pn) {
        Vector v = new Vector();
        synchronized(recentlyForwardedIDs) {
            Enumeration e = recentlyForwardedIDs.keys();
            while(e.hasMoreElements()) {
                Long l = (Long)e.nextElement();
                RecentlyForwardedItem item = (RecentlyForwardedItem)recentlyForwardedIDs.get(l);
                if(item.routedTo != pn) continue;
                if(item.successfullyForwarded) {
                    removeRecentlyForwardedItem(item);
                    v.add(item);
                }
            }
        }
        Logger.normal(this, "lostOrRestartedNode dumping "+v.size()+" swap requests for "+pn.getPeer());
        for(int i=0;i<v.size();i++) {
            RecentlyForwardedItem item = (RecentlyForwardedItem) v.get(i);
            // Just reject it to avoid locking problems etc
            Message msg = DMT.createFNPSwapRejected(item.incomingID);
            if(logMINOR) Logger.minor(this, "Rejecting in lostOrRestartedNode: "+item.incomingID+ " from "+item.requestSender);
            try {
                item.requestSender.sendAsync(msg, null, 0, null);
            } catch (NotConnectedException e1) {
                Logger.normal(this, "Both sender and receiver disconnected for "+item);
            }
        }
    }
    
    private void removeRecentlyForwardedItem(RecentlyForwardedItem item) {
    	if(logMINOR) Logger.minor(this, "Removing: "+item);
        if(item == null) {
            Logger.error(this, "removeRecentlyForwardedItem(null)", new Exception("error"));
        }
        recentlyForwardedIDs.remove(new Long(item.incomingID));
        recentlyForwardedIDs.remove(new Long(item.outgoingID));
    }
    
    private final long MAX_AGE = 7*24*60*60*1000;
    
    private final TimeSortedHashtable knownLocs = new TimeSortedHashtable();
    
    void registerLocationLink(double d, double t) {
    	if(logMINOR) Logger.minor(this, "Known Link: "+d+ ' ' +t);
    }
    
    void registerKnownLocation(double d, long uid) {
    	if(logMINOR) Logger.minor(this, "LOCATION: "+d+" UID: "+uid);
    	registerKnownLocation(d);
    }
    
    void registerKnownLocation(double d) {
    	if(logMINOR) Logger.minor(this, "Known Location: "+d);
        Double dd = new Double(d);
        long now = System.currentTimeMillis();
        
        synchronized(knownLocs) {
        	Logger.minor(this, "Adding location "+dd+" knownLocs size "+knownLocs.size());
        	knownLocs.push(dd, now);
        	Logger.minor(this, "Added location "+dd+" knownLocs size "+knownLocs.size());
        	knownLocs.removeBefore(now - MAX_AGE);
        	Logger.minor(this, "Added and pruned location "+dd+" knownLocs size "+knownLocs.size());
        }
		if(logMINOR) Logger.minor(this, "Estimated net size(session): "+knownLocs.size());
    }
    
    void registerLink(long uid1, long uid2) {
    	if(logMINOR) Logger.minor(this, "UID LINK: "+uid1+" , "+uid2);
    }
    
    //Return the estimated network size based on locations seen after timestamp or for the whole session if -1
    public int getNetworkSizeEstimate(long timestamp) {
   		return knownLocs.countValuesAfter(timestamp);
	}
    
    /**
     * Method called by Node.getKnownLocations(long timestamp)
     * 
     * @Return an array containing two cells : Locations and their last seen time for a given timestamp.
     */
    public Object[] getKnownLocations(long timestamp) {
    	synchronized (knownLocs) {
    		return knownLocs.pairsAfter(timestamp, new Double[knownLocs.size()]);
    	}
	}
    
	private static double[] extractLocs(LocationUIDPair[] pairs) {
		double[] locs = new double[pairs.length];
		for(int i=0;i<pairs.length;i++)
			locs[i] = pairs[i].location;
		return locs;
	}

	private static long[] extractUIDs(LocationUIDPair[] pairs) {
		long[] uids = new long[pairs.length];
		for(int i=0;i<pairs.length;i++)
			uids[i] = pairs[i].uid;
		return uids;
	}
}
