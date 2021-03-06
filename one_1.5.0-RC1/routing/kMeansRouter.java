/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package routing;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import routing.util.RoutingInfo;

import util.Tuple;

import core.Coord;
import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

import org.shogun.*;
import org.jblas.*;
import static org.shogun.Math.init_random;
import static org.shogun.LabelsFactory.to_multiclass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of Game router as described in
 * <I>Probabilistic routing in intermittently connected networks</I> by
 * Anders Lindgren et al.
 */
public class kMeansRouter extends ActiveRouter{
    static{
        System.load("/usr/local/lib/jni/libmodshogun.so");
        modshogun.init_shogun_with_defaults();
    }

    private static int countTotal=0;
   private static int start=0; // Transferring messages when start=1
   private static int nodeCount=-1; //to store the count of no of nodes
   private double zerothreshold; //to check how much of the encounter matrix has been filled

   //constant for default value of zeroThreshold
   public static final double DEFAULT_ZEROTHRESHOLD=0.25;
   //constant  for kmeans namespace
   public static final String kMeans_NS = "kMeansRouter";

   //if zerothreshold=0.25, it means 75% of the encounter matrix must be filled before we start the algorithm
   private double maxPossibleZeroes=nodeCount*nodeCount*zerothreshold;

   public static final String ZEROTHRESHOLD_S = "zerothreshold";

    /** number of encounters of every node with every other node*/
    private static int[][] encounters;

    /** identifier for the initial number of copies setting ({@value})*/
    public static final String NROF_COPIES = "nrofCopies";
    /** identifier for the binary-mode setting ({@value})*/
    public static final String BINARY_MODE = "binaryMode";

    /** Message property key */
    public static final String MSG_COUNT_PROPERTY = kMeans_NS + "." +
        "copies";

    protected int initialNrofCopies;
    protected boolean isBinary;

    /**
     * Constructor. Creates a new message router based on the settings in
     * the given Settings object.
     * @param s The settings object
     */
    public kMeansRouter(Settings s) {
        super(s);
        Settings kMeansSettings = new Settings(kMeans_NS);

        if (kMeansSettings.contains(ZEROTHRESHOLD_S)) {
            zerothreshold = kMeansSettings.getDouble(ZEROTHRESHOLD_S);
        }
        else {
            zerothreshold = DEFAULT_ZEROTHRESHOLD;
        }

        initialNrofCopies = kMeansSettings.getInt(NROF_COPIES);
        isBinary = kMeansSettings.getBoolean( BINARY_MODE);
    }

    /**
     * Copyconstructor.
     * @param r The router prototype where setting values are copied from
     */
    protected kMeansRouter(kMeansRouter r) {
        super(r);
        this.zerothreshold=r.zerothreshold;
        this.initialNrofCopies = r.initialNrofCopies;
        this.isBinary = r.isBinary;
    }

     void checkStart()  {

        int countZeroes=0;

        int i,j;

        for(i=0;i<nodeCount;i++) {
            for(j=0;j<nodeCount;j++) {
                if(encounters[i][j]==0){
                    countZeroes++;
                }
            }
        }
        if(countZeroes < maxPossibleZeroes)
        {
            start=1; //set start to 1 if the encounter matrix satisfies threshold for no of zero values
        }

        return;

    }   //end of checkStart

    @Override
    public int receiveMessage(Message m, DTNHost from) {
        return super.receiveMessage(m, from);
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);

        assert nrofCopies != null : "Not a SnW message: " + msg;

        if (isBinary) {
            /* in binary S'n'W the receiving node gets ceil(n/2) copies */
            nrofCopies = (int)java.lang.Math.ceil(nrofCopies/2.0);
        }
        else {
            /* in standard S'n'W the receiving node gets only single copy */
            nrofCopies = 1;
        }

        msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
        return msg;
    }

    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());

        msg.setTtl(this.msgTtl);
        msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
        addToMessages(msg, true);
        return true;
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);

        if (con.isUp()) {
            DTNHost otherHost = con.getOtherNode(getHost());

            if(nodeCount==-1)
            {
                nodeCount=otherHost.getHosts().size();
                maxPossibleZeroes=nodeCount*nodeCount*zerothreshold;
            }

            updateEncounters(getHost(),otherHost);
            //to set start
            if(start==0) {
                checkStart();
            }

        }
    }


    /**
     * Updates the value of encounters when two nodes come in contact with each other
     * @param host1 first node
     * @param host2 second node
     */
    public void updateEncounters(DTNHost host1, DTNHost host2) {
        //each message has different destination and we'll need encounters of every node with the destination(which is changing with each message) in same time instance, hence we've decided to use a 2D array
        if (this.encounters == null) {
            this.encounters=new int[host1.getHosts().size()][host1.getHosts().size()]; //TODO:replace 126 by hosts.size
        }
        kMeansRouter othRouter = (kMeansRouter)host2.getRouter();
        kMeansRouter myRouter = (kMeansRouter)host1.getRouter();


        if(myRouter!=othRouter)
        {
            this.encounters[host1.getAddress()][host2.getAddress()]++;
        }
        else
        {
            this.encounters[host1.getAddress()][host2.getAddress()]++;
            this.encounters[host2.getAddress()][host1.getAddress()]++;
        }
        //System.out.println(host1.getAddress()+ " " + sumEncounters.get(host1));
        //System.out.println(host2.getAddress()+ " " + sumEncounters.get(host2));
    }

    /**
     * Returns the current encounter (E) value for a host
     * @param host1 The host to look the E for
     * @param host2 The node with respect to which we have to return the encounters
     * @return the current E value
     */
    public int getEncounter(DTNHost host1,DTNHost host2){
        return this.encounters[host1.getAddress()][host2.getAddress()];
    }

    @Override
    public void update() {
        super.update();
        if (!canStartTransfer() ||isTransferring()) {
            return; // nothing to transfer or is currently transferring
        }

        // try messages that could be delivered to final recipient

        if (exchangeDeliverableMessages() != null) {
      //      System.out.print("x");
            return;
        }


        //tryOtherMessages();

        //To begin simulation when start=1
        //i.e. when the encounter matrix has no zero value
         if(start==1)
        {
   //         countTotal++;
     //       System.out.println(countTotal);
            tryOtherMessages();
        }

    }

    /**
     * Tries to send all other messages to all connected hosts
     * @return The return value of {@link #tryMessagesForConnected(List)}
     */
    private Tuple<Message, Connection> tryOtherMessages() {
        List<Tuple<Message, Connection>> messages =
            new ArrayList<Tuple<Message, Connection>>();

        /* create a list of SAWMessages that have copies left to distribute */
        @SuppressWarnings(value = "unchecked")
        List<Message> msgCollection = sortByQueueMode(getMessagesWithCopiesLeft());

        //System.out.println("poop");

        for(Message m : msgCollection){

            DTNHost dest = m.getTo();
            int noOfFeatures=4;
            double[][] featureMatrix=new double[getConnections().size()][noOfFeatures];
            int i=0;

//           System.out.println("no. of connections="+getConnections().size());
         //   System.out.println("poop");
           // System.out.println("dest:: "+dest.getAddress());
            //to get the feature matrix of the neighbours.
            for (Connection con : getConnections()){

                //DTNHost me = getHost();
                DTNHost other = con.getOtherNode(getHost());
             //   System.out.println("other:: "+other.getAddress());
                if(other==dest){
               //  System.out.print("x");
                  //  System.out.println(m.getId());
                   //System.out.println(getHost() +":::"+other.getAddress());
                }
                //kMeansRouter othRouter = (kMeansRouter)other.getRouter();

                featureMatrix[i][0]=getEncounter(other,dest);
                featureMatrix[i][1]=getDistFor(other,dest);
                featureMatrix[i][2]=other.getBufferOccupancy();
                featureMatrix[i][3]=msgSuccess[other.getAddress()];
               // System.out.println(msgSuccess[other.getAddress()]);
                //System.out.println(other.getPath());

                i++;
            }
            //System.out.println("poop1");
            //convert the java Double Matrix to jblas DubleMatrix
            normalize(featureMatrix,getConnections().size(),noOfFeatures);
            DoubleMatrix features = new DoubleMatrix(featureMatrix);
            //System.out.println(features);
            //System.out.println("poop1");
            //load the shogun library

            //System.out.println("poopk");
            //initialize shogun with default values



            //number of clusters=2
            int k=2;
            //init_random(17);
            //DoubleMatrix fm_train = Load.load_numbers("~/projects/shogun/data/fm_train_real.dat");

            //convert jblas features to RealFeatures that are compatible with shogun
            features=features.transpose();


            RealFeatures feats_train = new RealFeatures(features);

            //pre-processing
            //mean normalization
        //  NormOne preproc = new NormOne();

        //  preproc.init(feats_train);

            //feats_train.add_preprocessor(new NormOne());
            //feats_train.add_preprocessor(new LogPlusOne());

            //feats_train.apply_preprocessor();

            //System.out.println("poopk");

            //System.out.println(feats_train);

                                                                //???
            EuclideanDistance distance=new EuclideanDistance(feats_train,feats_train);


            KMeans kmeans=new KMeans(k,distance);
            //kmeans.set_use_kmeanspp(true);
            kmeans.train();


            DoubleMatrix cluster_centers=kmeans.get_cluster_centers();
            cluster_centers=cluster_centers.transpose();
            //DoubleMatrix cluster_radiuses=kmeans.get_radiuses();
            DoubleMatrix result=to_multiclass(kmeans.apply()).get_labels();

            //System.out.println("poop2");

            //System.out.println(cluster_centers);
            //System.out.println(cluster_centers.columns);
            //System.out.println(cluster_radiuses);

            //System.out.println(result);
            //System.out.println(cluster_radiuses.columns);

            int positive_cluster=1;
            //find out the positive cluster


                if(cluster_centers.get(0,1)<cluster_centers.get(1,1))
                {
                    //distance of cluster1<distance of cluster 2
                    positive_cluster=0;
                }
                else if(cluster_centers.get(0,1)>cluster_centers.get(1,1))
                {
                    //distance of cluster1>distance of cluster 2
                    positive_cluster=1;
                }
                else
                {
                    //distance of cluster1==distance of cluster 2
                    //if distances are equal then compare by encounters
                    if(cluster_centers.get(0,0)>cluster_centers.get(1,0))
                    {
                        positive_cluster=0;
                    }
                    else
                    {
                        positive_cluster=1;
                    }
                }

           // System.out.println("poop3");
          //  System.out.println("positive_cluster="+ positive_cluster);

            //modshogun.exit_shogun();

            //loop over neighbors for transferring messages
            i=0;
            for(Connection con : getConnections())
            {
                DTNHost other=con.getOtherNode(getHost());
                kMeansRouter othRouter = (kMeansRouter)other.getRouter();
                if(othRouter.isTransferring()){
                    continue;
                }
                if(othRouter.hasMessage(m.getId())){
                    continue;
                }
                if(result.get(0,i)==positive_cluster)
                {
                    messages.add(new Tuple<Message, Connection>(m,con));
                }
                i++;
            }  //end of for loop

            /*
            //flooding
            for(Connection con : getConnections())
                {
                    DTNHost other=con.getOtherNode(getHost());
                    kMeansRouter othRouter = (kMeansRouter)other.getRouter();
                    if(othRouter.isTransferring()){
                        continue;
                    }
                    if(othRouter.hasMessage(m.getId())){
                        continue;
                    }
                    messages.add(new Tuple<Message, Connection>(m,con));
                }
            */
        }
        if (messages.size() == 0) {
            return null;
        }

        // sort the message-connection tuples (i dont's see the need to sort:-Kunal)
        //Collections.sort(messages, new TupleComparator());
        return tryMessagesForConnected(messages);   // try to send messages
    }

/**
     * Creates and returns a list of messages this router is currently
     * carrying and still has copies left to distribute (nrof copies > 1).
     * @return A list of messages that have copies left
     */
    protected List<Message> getMessagesWithCopiesLeft() {
        List<Message> list = new ArrayList<Message>();

        for (Message m : getMessageCollection()) {
            Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);
            assert nrofCopies != null : "SnW message " + m + " didn't have " +
                "nrof copies property!";
            if (nrofCopies > 1) {
                list.add(m);
            }
        }

        return list;
    }

    /**
     * Called just before a transfer is finalized (by
     * {@link ActiveRouter#update()}).
     * Reduces the number of copies we have left for a message.
     * In binary Spray and Wait, sending host is left with floor(n/2) copies,
     * but in standard mode, nrof copies left is reduced by one.
     */
    @Override
    protected void transferDone(Connection con) {
        Integer nrofCopies;
        String msgId = con.getMessage().getId();
        /* get this router's copy of the message */
        Message msg = getMessage(msgId);

        if (msg == null) { // message has been dropped from the buffer after..
            return; // ..start of transfer -> no need to reduce amount of copies
        }

        /* reduce the amount of copies left */
        nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
        if (isBinary) {
            nrofCopies /= 2;
        }
        else {
            nrofCopies--;
        }
        msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
    }

//calculates euclidean distance between two nodes
//index1- index of the first node
//index2- index of the second node
private double euclideanDist(double[][] featureMatrix,int rows,int columns,int index1,int index2)
{
    double sum=0;
    for(int i=0;i<columns;i++)
    {
        sum=sum+java.lang.Math.pow((featureMatrix[index1][i]-featureMatrix[index2][i]),2);
    }
    java.lang.Math.sqrt(sum);
    return sum;
}

//calculates euclidean distance of a node from origin
//index1- index of the node
private double euclideanDist(double[][] featureMatrix,int rows,int columns,int index1)
{
    double sum=0;
    for(int i=0;i<columns;i++)
    {
        sum=sum+java.lang.Math.pow((featureMatrix[index1][i]),2);
    }
    java.lang.Math.sqrt(sum);
    return sum;
}

//to normalize the featureMatrix using mean normalization
private void normalize(double[][] featureMatrix,int rows,int columns)
{
    double sum,min,max,diff;
    for(int i=0;i<columns;i++)
    {
        sum=0;
        max=-1;
        min=Double.POSITIVE_INFINITY;
        for(int j=0;j<rows;j++)
        {
            if(featureMatrix[j][i]>max)
            {
                max=featureMatrix[j][i];
            }
            if(featureMatrix[j][i]<min)
            {
                min=featureMatrix[j][i];
            }
        }
        diff=max-min;
        for(int j=0;j<rows;j++)
        {
            if(diff==0){
                featureMatrix[j][i]=0;
            }
            else{
                featureMatrix[j][i]=(featureMatrix[j][i]-min)/diff;
            }

        }
    }
}
/**
* Returns the current distance between dest node and the nextHost node
* @param dest The destination node
* @param nextHost The node from which we want to calculate the diatance from
* @return the current distance
*/
private double getDistFor(DTNHost dest,DTNHost nextHost)
{
    Coord destLoc = dest.getLocation();
    Coord nextHostLoc = nextHost.getLocation();
    double x1 = nextHostLoc.getX();
    double y1 = nextHostLoc.getY();
    double x2 = destLoc.getX();
    double y2 = destLoc.getY();
    double a = y1 - y2;
    double b = x2 - x1;
    double c = y2*x1 - y1*x2;
    double dist = java.lang.Math.pow((a*a+b*b),0.5);
    if(dist<0) dist = -dist;
    return dist;
}

//display matrix (for debugging purposes)
private void dispMatrix(double[][] featureMatrix,int rows,int columns)
{
    for(int i=0;i<rows;i++)
    {
        for(int j=0;j<columns;j++)
        {
            System.out.print(featureMatrix[i][j]);
            System.out.print("\t");
        }
        System.out.println();
    }
}
    @Override
    public MessageRouter replicate() {
        kMeansRouter r = new kMeansRouter(this);
        return r;
    }

}