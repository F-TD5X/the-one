package routing;

import core.*;
import util.Tuple;

import java.util.*;

public class AntRouter extends ActiveRouter {

    public static final String ANT_NS = "AntRouter";

    public static double ANT_INFO_UPDATE;
    public static double UPDATE_INTERVAL;

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
    public int receiveMessage(Message msg, DTNHost from){
        if (msg.getProperty("isAntPacket") != null) {
            if (predictions.containsKey(from))
                predictions.put(from, predictions.get(from) + ANT_INFO_UPDATE);
        }
        return super.receiveMessage(msg,from);
    }

    /**
     * While connection changed, send ant packats and update routing table.
     * @param conn
     */
    @Override
    public void changedConnection(Connection conn){
        if (conn.isUp()){
            DTNHost other_host = conn.getOtherNode(getHost());
            updateKnownHost(other_host);
            doAntTravel(conn);
        }
    }

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
            }
        }
        if (messages.size() == 0) return null;

        // Collections.sort(messages, new TupleComparetor());
        // try to send messages
        return tryMessagesForConnected(messages);
    }

    /**
     * send Ant Packet to every host this Node known via this connection..
     * and Ant Packet is a small packet.
     * @param conn Connection to be used.
     */
    private void doAntTravel(Connection conn){
        double sim_time = SimClock.getTime();
        // if needs send ant packet
        if (sim_time - last_update_time - UPDATE_INTERVAL < 0.0000006){
            List<Tuple<Message,Connection>> ant_message_list = new ArrayList<>();
            for (DTNHost host:known_host){
                Message msg = new Message(getHost(),host,"Ant Packet",62);
                msg.addProperty("begin",getHost());
                msg.addProperty("end",host);
                ant_message_list.add(new Tuple<>(msg,conn));
            }
            tryMessagesForConnected(ant_message_list);
        }
    }

    /**
     * Update known host for generate ant packet.
     * @param host DTNHost
     */
    private void updateKnownHost(DTNHost host){
        if (!known_host.contains(host)) known_host.add(host);
    }

    /**
     * Return prediction value for host or return 0 if host is not included in table.
     * @param host DTNhost
     * @return the current prediction value
     */
    public double getPredicitonForHost(DTNHost host){
        if (predictions.containsKey(host))
            return predictions.get(host);
        else
            return 0;
    }

    @Override
    public AntRouter replicate(){ return new AntRouter(this);}
}
