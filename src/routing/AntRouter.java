package routing;

import core.*;
import routing.util.RoutingInfo;
import util.Tuple;

import java.util.*;

public class AntRouter extends ActiveRouter {

    private static final String ANT_NS = "AntRouter";

    private static double UPDATE_INTERVAL;               // Update Interval
    private static double PRED_INC;                      // prediction increased value per times.
    private static double PRED_DEC;                      // prediction decreased value pre times.
    private static double PRED_DEC_RATE;                 // prediction decreasing rate for hops.

    private Map<DTNHost, Double> predictions;            // Ant Router predictions table.
    private Map<DTNHost, Double> predictions_update;     // predictions update time.
    private List<DTNHost> known_host;                   // Discovered Host List.
    private double last_update_time;                    // lase update time.


    /**
     * Constructor. Create Message Router based on settings in Settings Object.
     *
     * @param s Settings Object
     */
    public AntRouter(Settings s) {
        super(s);
        Settings ant_router_settings = new Settings(ANT_NS);

        predictions = new HashMap<>();
        predictions_update = new HashMap<>();
        known_host = new ArrayList<>();

        UPDATE_INTERVAL = ant_router_settings.getDouble("UPDATE_INTERVAL");

        PRED_INC = ant_router_settings.getDouble("PRED_INC");
        PRED_DEC = ant_router_settings.getDouble("PRED_DEC");
        PRED_DEC_RATE = ant_router_settings.getDouble("PRED_DEC_RATE");
    }

    /**
     * Copy Constructor.
     *
     * @param r AntRouter Object.
     */
    private AntRouter(AntRouter r) {
        super(r);

        this.predictions = new HashMap<>();
        this.known_host = new ArrayList<>();
        this.predictions_update = new HashMap<>();
    }


    /**
     * update via timer interval.
     */
    @Override
    public void update() {
        super.update();
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

            // Direct from is updated for PRED_INC
            if (predictions.containsKey(from)) {
                predictions.put(from, predictions.get(from) + PRED_INC);
                predictions_update.put(from, SimClock.getTime());
            } else {
                predictions.put(from, PRED_INC);
                predictions_update.put(from, SimClock.getTime());
            }

            // Predictions will decrease for hops
            DTNHost host = msg.getFrom();
            int HopCount = msg.getHopCount();
            if (predictions.containsKey(host)) {
                predictions.put(host, predictions.get(host) + PRED_INC * Math.pow(PRED_DEC_RATE, HopCount - 1));
                predictions_update.put(host, SimClock.getTime());
            } else {
                predictions.put(host, PRED_INC * Math.pow(PRED_DEC_RATE, HopCount - 1));
                predictions_update.put(host, SimClock.getTime());
            }

            // If ECHO, send Reply
            if (msg.getId().contains("Echo")) {
                Message return_msg = new Message(msg.getTo(), msg.getFrom(), "Ant Reply --" + msg.getFrom() + "-" + msg.getTo(), msg.getSize());
                createNewMessage(return_msg);
            }
        }

        return super.receiveMessage(msg, from);
    }

    /**
     * While connection changed, send ant packats and update routing table.
     *
     * @param conn Connection Object
     */
    @Override
    public void changedConnection(Connection conn) {
        super.changedConnection(conn);

        if (conn.isUp()) {
            DTNHost other_host = conn.getOtherNode(getHost());
            updateKnownHost(other_host);
        }
    }

    @Override
    public RoutingInfo getRoutingInfo() {
        RoutingInfo top = super.getRoutingInfo();
        RoutingInfo ret = new RoutingInfo(predictions.size() + " delivery prediction(s)");

        for (Map.Entry<DTNHost, Double> entry : predictions.entrySet()) {
            DTNHost host = entry.getKey();
            double preds = entry.getValue();

            ret.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", host, preds)));
        }

        top.addMoreInfo(ret);
        return top;
    }

    /**
     * If passed UPDATE_INTERVAL, generate and send ANT_ECHO_PACKET
     * And decrease predictions anytime.
     */
    private void updateAntTable() {

        double now_time = SimClock.getTime();
        if ((now_time - last_update_time) / UPDATE_INTERVAL - 1 >= 0) {

            for (DTNHost host : known_host) {
                if (predictions_update.containsKey(host)) {
                    double update_time = predictions_update.get(host);
                    double pred = predictions.get(host);

                    if (pred - PRED_DEC > 0)
                        predictions.put(host, pred - PRED_DEC);
                    else
                        predictions.put(host, 0.0);

                    if (now_time - update_time - UPDATE_INTERVAL > 0) {
                        Message msg = new Message(getHost(), host, "Ant Echo --" + getHost() + "-" + host, 64);
                        createNewMessage(msg);
                        predictions_update.put(host, now_time);
                    }
                } else {
                    Message msg = new Message(getHost(), host, "Ant Echo --" + getHost() + "-" + host, 64);
                    createNewMessage(msg);
                }
            }

            last_update_time = now_time;
        }

    }

    /**
     * Get the prediction of the host in this router's predictions table
     *
     * @param host DTNHost Object
     * @return double value as prediction
     */
    public double getPred(DTNHost host) {
        if (predictions.containsKey(host)) return predictions.get(host);
        return 0.0;
    }

    /**
     * Tries to send all other messages to all connected hosts ordered by
     * their probability
     *
     * @return The return value of {@link #tryMessagesForConnected(List)}
     */
    private Tuple<Message, Connection> doPrivateMessage() {
        List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();

        Collection<Message> msg_collection = getMessageCollection();

        for (Connection conn : getConnections()) {
            DTNHost other_host = conn.getOtherNode(getHost());
            AntRouter other_router = (AntRouter) other_host.getRouter();

            // skip host is transferring.
            if (other_router.isTransferring()) continue;

            for (Message msg : msg_collection) {
                if (other_router.hasMessage(msg.getId())) continue;

                if (getPred(msg.getTo()) > other_router.getPred(msg.getTo())) {
                    messages.add(new Tuple<>(msg, conn));
                }
            }
        }
        if (messages.size() == 0) return null;

        messages.sort(new TupleComparator());

        // try to send messages
        return tryMessagesForConnected(messages);
    }


    /**
     * Update known host for generating ant packet.
     *
     * @param host DTNHost
     */
    private void updateKnownHost(DTNHost host) {
        if (!known_host.contains(host)) known_host.add(host);
    }

    @Override
    public AntRouter replicate() {
        return new AntRouter(this);
    }

    /**
     * Sort On_send_messages predictions. Bigger is higher.
     */
    private class TupleComparator implements Comparator
            <Tuple<Message, Connection>> {
        public int compare(Tuple<Message, Connection> tp1, Tuple<Message, Connection> tp2) {
            double pred1 = ((AntRouter) tp1.getValue().getOtherNode(getHost()).getRouter()).getPred(tp1.getKey().getTo());
            double pred2 = ((AntRouter) tp2.getValue().getOtherNode(getHost()).getRouter()).getPred(tp2.getKey().getTo());

            if (pred2 - pred1 == 0) return 0;
            else if (pred2 - pred1 < 0) return -1;
            else return 1;
        }
    }
}
