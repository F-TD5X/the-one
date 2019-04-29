/**
 * Author: F_TD5X
 */

package routing;

import core.*;
import routing.util.RoutingInfo;
import util.Tuple;

import java.lang.reflect.AnnotatedType;
import java.util.*;

public class AntRouter extends ActiveRouter{

    /** Ant Router's setting namespace ({@value})*/
    private static String ANT_NS = "AntRouter";

    /** update interval */
    private static double UPDATE_INTERVAL;
    private static double DEFAULT_UPDATE_INTERVAL = 1000;

    /** increase value of predictions */
    private static double PREDS_INC;
    private static double DEFAULT_PREDS_INC = 10;


    /** decrease rate of predictions */
    private static double PREDS_DEC;
    private static double DEFAULT_PREDS_DEC = 0.5;

    private static int ANTS_PRE_MSG;
    private static int DEFAULT_ANTS_PRE_MSG = 50;


    /** delivery predictabilities */
    private Map<DTNHost,Double> predictions;

    private Map<DTNHost,Double> update_time;

    public AntRouter(Settings s){
        super(s);
        Settings antRouterSettings = new Settings(ANT_NS);

        if(antRouterSettings.contains("UPDATE_INTERVAL"))
            UPDATE_INTERVAL = antRouterSettings.getDouble("UPDATE_INTERVAL");
        else
            UPDATE_INTERVAL = DEFAULT_UPDATE_INTERVAL;

        if(antRouterSettings.contains("PREDS_INC"))
            PREDS_INC = antRouterSettings.getDouble("PREDS_INC");
        else
            PREDS_INC = DEFAULT_PREDS_INC;

        if(antRouterSettings.contains("PREDS_DEC"))
            PREDS_DEC = antRouterSettings.getDouble("PREDS_DEC");
        else
            PREDS_DEC = DEFAULT_PREDS_DEC;

        if(antRouterSettings.contains("ANTS_PER_MSG"))
            ANTS_PRE_MSG = antRouterSettings.getInt("ANTS_PER_MSG");
        else
            ANTS_PRE_MSG = DEFAULT_ANTS_PRE_MSG;

        Init();
    }

    public AntRouter(AntRouter r){
        super(r);
        Init();
    }

    private void Init(){
        predictions = new HashMap<>();
        update_time = new HashMap<>();
    }

    @Override
    public void changedConnection(Connection conn){
        super.changedConnection(conn);
    }

    @Override
    public int receiveMessage(Message msg,DTNHost from){
        int ret = super.receiveMessage(msg,from);

        double preds = getPreds(from) + PREDS_INC * ((Number)(msg.getProperty("ants"))).intValue();
        predictions.put(from,preds);
        update_time.put(from,SimClock.getTime());

        return ret;
    }

    @Override
    public boolean createNewMessage(Message msg){
        // add property to set ants to transfer this message.
        if(msg.getProperty("ants")==null) {
            msg.addProperty("ants", ANTS_PRE_MSG);
        }
        return super.createNewMessage(msg);
    }

    @Override
    public void update(){
        super.update();

        // nothing to transfer or is transferring
        if(!canStartTransfer()|| isTransferring())return;

        // try messages that could be delivered to final recipient
        if(exchangeDeliverableMessages() != null)return;

        tryOtherMessage();

    }

    @Override
    public MessageRouter replicate(){
        return new AntRouter(this);
    }

    @Override
    public RoutingInfo getRoutingInfo(){
        RoutingInfo top = super.getRoutingInfo();
        RoutingInfo ret = new RoutingInfo(predictions.size()+" delivery predictabilities");

        for(Map.Entry<DTNHost,Double> entry : predictions.entrySet()){
            DTNHost host  = entry.getKey();
            updatePreds(host);
            double preds = entry.getValue();

            ret.addMoreInfo(new RoutingInfo(String.format("%s : %.6f",host,preds)));
        }
        top.addMoreInfo(ret);
        return top;
    }

    /**
     * Get this router's delivery prediction about the host.
     * @param host Target host
     * @return a double value(prediction of the host).
     */
    private double getPreds(DTNHost host){
        if(predictions.containsKey(host)){
            updatePreds(host);
            return predictions.get(host);
        }
        return 1.0;
    }

    /**
     * update  predictions via time pass
     * predictions is decreasing
     * @param host Target DTNHost to update
     */
    private void updatePreds(DTNHost host){
        double now = SimClock.getTime();
        int pass = (int)((now - update_time.get(host))/UPDATE_INTERVAL);
        predictions.put(host,predictions.get(host) * Math.pow(PREDS_DEC,pass));
        update_time.put(host,now);
    }

    /**
     * Tries to send all other messages to all connected hosts ordered by their delivery probability.
     * @return value of {@link #tryMessagesForConnected(List)}
     */
    private Tuple<Message,Connection> tryOtherMessage(){
        List<Tuple<Message,Connection>> messages = new ArrayList<>();

        Collection<Message> msgCollection = getMessageCollection();

        /*
        for(Connection conn:getConnections()){
            DTNHost other_host = conn.getOtherNode(getHost());
            AntRouter other_router = (AntRouter)other_host.getRouter();

            if (other_router.isTransferring()) continue;

            for(Message msg:msgCollection) {
                if (other_router.hasMessage(msg.getId())) continue;
                if (other_router.getPreds(msg.getTo()) > getPreds(msg.getTo())) {
                    messages.add(new Tuple<>(msg, conn));
                }
            }
        }
        */

        for(Message msg:msgCollection){
            int ants = ((Number)msg.getProperty("ants")).intValue();
            double sum_of_preds = 0;

            // get sum of predictions
            for(Connection conn:getConnections()){
                AntRouter other_router = (AntRouter)conn.getOtherNode(getHost()).getRouter();
                if(other_router.isTransferring())continue;
                if(other_router.hasMessage(msg.getId()))continue;
                if(other_router.getPreds(msg.getTo()) > getPreds(msg.getTo())){
                    sum_of_preds += other_router.getPreds(msg.getTo());
                }
            }

            // let ants go and separated via preds divided by sum_of_preds
            for(Connection conn:getConnections()){
                AntRouter other_router = (AntRouter)conn.getOtherNode(getHost()).getRouter();
                msg.updateProperty("ants",(int)(ants * (other_router.getPreds(msg.getTo())/sum_of_preds)));
                messages.add(new Tuple<>(msg,conn));
            }
        }
        if(messages.size() == 0) return null;

        messages.sort(new TupleComparator());
        return tryMessagesForConnected(messages);
    }

    /**
     * a comparator of Tuple<Message,Connnection>
     */
    private class TupleComparator implements Comparator<Tuple<Message,Connection>>{
        public int compare(Tuple<Message,Connection> t1,Tuple<Message,Connection> t2){
            double p1 = ((AntRouter)t1.getValue().getOtherNode(getHost()).getRouter()).getPreds(t1.getKey().getTo());
            double p2 = ((AntRouter)t2.getValue().getOtherNode(getHost()).getRouter()).getPreds(t2.getKey().getTo());

            if(p2 - p1 == 0)
                return compareByQueueMode(t1.getKey(),t2.getKey());
            else if(p2-p1< 0)return -1;
            else return 1;
        }
    }
}