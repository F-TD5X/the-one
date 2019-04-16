package routing;

import core.*;
import util.Tuple;

import java.util.*;

public class AntRouter extends ActiveRouter {

    public static final String ANT_NS = "AntRouter";

    public static double ANT_INFO_UPDATE;
    public static double UPDATE_INTERVAL;
    public static double PRED_INC;
    public static double PRED_DEC;

    private Map<DTNHost,Double> predictions;
    private List<DTNHost> known_host;
    private double last_update_time;
    private int ant_id = -1;


    /**
     * Constructor. Create Message Router based on settings in Settings Object.
     * @param s Settings Object
     */
    public AntRouter(Settings s) {
        super(s);
        Settings ant_router_settings = new Settings(ANT_NS);

        ANT_INFO_UPDATE = ant_router_settings.getDouble("ANT_INFO_UPDATE");
        UPDATE_INTERVAL = ant_router_settings.getDouble("UPDATE_INTERVAL");

        PRED_INC = ant_router_settings.getDouble("PRED_INC");
        PRED_DEC = ant_router_settings.getDouble("PRED_DEC");
    }

    /**
     * Copy Constructor.
     * @param r AntRouter Object.
     */
    private AntRouter(AntRouter r) {
        super(r);
    }


    /**
     * update via timer interval.
     */
    @Override
    public void update() {
        updateAntTable();
        // nothing to do or can't transfer message.
        if (isTransferring() || !canStartTransfer())
            return;

        // try to transfer message to final replicate.
        if (exchangeDeliverableMessages() != null)
            return;

        // try find hosts / find route / transfer message by route
        doPrivateMessage();
    }

    @Override
    public int receiveMessage(Message msg, DTNHost from) {

        // If Ant Packet, update predictions
        if (msg.getId().contains("Ant")) {
            if (predictions.containsKey(from))
                predictions.put(from, predictions.get(from) + PRED_INC);
            else
                predictions.put(from,0.0);

            // If ECHO, send Reply
            if (msg.getId().contains("Echo")) {
                Message return_msg = new Message(msg.getTo(), msg.getFrom(), "Ant Reply" + SimClock.getTime(), msg.getSize());
                createNewMessage(return_msg);
            }
        }
        return super.receiveMessage(msg,from);
    }

    /**
     * While connection changed, send ant packats and update routing table.
     * @param conn Connection Object
     */
    @Override
    public void changedConnection(Connection conn){
        if (conn.isUp()){
            DTNHost other_host = conn.getOtherNode(getHost());
            updateKnownHost(other_host);
        }
    }

    /**
     * If passed UPDATE_INTERVAL, generate and send ANT_ECHO_PACKET
     */
    private void updateAntTable(){
        double now_time = SimClock.getTime();
        if ((now_time - last_update_time) / UPDATE_INTERVAL - 1 >= 0) {
            for (DTNHost host : known_host) {
                Message msg = new Message(getHost(), host, "Ant Echo" + SimClock.getTime(), 2);
                createNewMessage(msg);
            }
        }
    }

    /**
     * Get the prediction of the host in this router's predictions table
     * @param host DTNHost Object
     * @return double value as prediction
     */
    public double getPred(DTNHost host){
        if (predictions.containsKey(host)) return predictions.get(host);
        return 0.0;
    }

    /**
     * Tries to send all other messages to all connected hosts ordered by
     * their probability
     * @return The return value of {@link #tryMessagesForConnected(List)}
     */
    private Tuple<Message, Connection> doPrivateMessage(){
        List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();

        Collection<Message> msg_collection = getMessageCollection();

        for (Connection conn:getConnections()){
            DTNHost other_host = conn.getOtherNode(getHost());
            AntRouter other_router = (AntRouter)other_host.getRouter();

            // skip host is transferring.
            if (other_router.isTransferring()) continue;

            for (Message msg:msg_collection){
                if (other_router.hasMessage(msg.getId())) continue;

                if(getPred(msg.getTo()) > other_router.getPred(msg.getTo())){
                    messages.add(new Tuple<>(msg,conn));
                }
            }
        }
        if (messages.size() == 0) return null;

        Collections.sort(messages, new TupleComparator());

        // Collections.sort(messages, new TupleComparetor());
        // try to send messages
        return tryMessagesForConnected(messages);
    }



    /**
     * Update known host for generate ant packet.
     * @param host DTNHost
     */
    private void updateKnownHost(DTNHost host){
        if (!known_host.contains(host)) known_host.add(host);
    }

    @Override
    public AntRouter replicate(){ return new AntRouter(this);}

    /**
     * Sort On_send_messages predictions. Bigger is higher.
     */
    private class TupleComparator implements Comparator
        <Tuple<Message,Connection>>{
        public int compare(Tuple<Message, Connection> tp1, Tuple<Message, Connection> tp2) {
            double pred1 = ((AntRouter)tp1.getValue().getOtherNode(getHost()).getRouter()).getPred(tp1.getKey().getTo());
            double pred2 = ((AntRouter)tp2.getValue().getOtherNode(getHost()).getRouter()).getPred(tp2.getKey().getTo());

            if (pred2 - pred1 == 0) return 0;
            else if (pred2 - pred1 < 0 ) return -1;
            else return 1;
        }
    }
}
