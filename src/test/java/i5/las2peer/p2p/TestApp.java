package i5.las2peer.p2p;

import i5.las2peer.p2p.pastry.PastryTestMessage;
import rice.p2p.commonapi.Application;
import rice.p2p.commonapi.Endpoint;
import rice.p2p.commonapi.Id;
import rice.p2p.commonapi.Message;
import rice.p2p.commonapi.Node;
import rice.p2p.commonapi.NodeHandle;
import rice.p2p.commonapi.RouteMessage;

public class TestApp implements Application {

	public static final String FREEPASTRY_APPLICATION_CODE = "i5.las2peer-node-application";

	/**
	 * The Endpoint represents the underlining node. By making calls on the Endpoint, it assures that the message will
	 * be delivered to a MyApp on whichever node the message is intended for.
	 */
	protected Endpoint endpoint;

	public TestApp(Node node) {
		// We are only going to use one instance of this application on each
		// PastryNode
		this.endpoint = node.buildEndpoint(this, FREEPASTRY_APPLICATION_CODE);

		// the rest of the initialization code could go here

		// now we can receive messages
		this.endpoint.register();
	}

	/**
	 * Called to route a message to the id
	 * 
	 * @param id The target id the message should be send to.
	 */
	public void routeMyMsg(Id id) {
		System.out.println("\t" + this + " sending to " + id);

		Message msg = new PastryTestMessage(endpoint.getId(), id);
		endpoint.route(id, msg, null);
	}

	/**
	 * Called to directly send a message to the nh
	 * 
	 * @param nh The node handle the message should be send to.
	 */
	public void routeMyMsgDirect(NodeHandle nh) {
		System.out.println("\t" + this + " sending direct to " + nh);

		Message msg = new PastryTestMessage(endpoint.getId(), nh.getId());
		endpoint.route(null, msg, nh);
	}

	/**
	 * Called when we receive a message.
	 * 
	 * @param id {@inheritDoc}
	 * @param message {@inheritDoc}
	 */
	@Override
	public void deliver(Id id, Message message) {
		System.out.println("\t\t\t" + this + " received " + message);
	}

	/**
	 * Called when you hear about a new neighbor. Don't worry about this method for now.
	 * 
	 * @param handle {@inheritDoc}
	 * @param joined {@inheritDoc}
	 */
	@Override
	public void update(NodeHandle handle, boolean joined) {
		System.out.println("\t\t\t\t new neighbour!: " + handle);
	}

	/**
	 * Called a message travels along your path. Don't worry about this method for now.
	 * 
	 * @param message {@inheritDoc}
	 * @return {@inheritDoc}
	 */
	@Override
	public boolean forward(RouteMessage message) {
		System.out.println("\t" + this + " forwarding message " + message);

		return true;
	}

	@Override
	public String toString() {
		return "TestApplication: " + endpoint.getId();
	}
}
