package i5.las2peer.p2p;

import i5.las2peer.api.logging.MonitoringEvent;
import i5.las2peer.api.persistency.EnvelopeException;
import i5.las2peer.api.persistency.EnvelopeNotFoundException;
import i5.las2peer.api.security.AgentException;
import i5.las2peer.api.security.AgentNotFoundException;
import i5.las2peer.classLoaders.L2pClassManager;
import i5.las2peer.classLoaders.libraries.SharedStorageRepository;
import i5.las2peer.communication.Message;
import i5.las2peer.communication.MessageException;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.p2p.pastry.MessageEnvelope;
import i5.las2peer.p2p.pastry.NodeApplication;
import i5.las2peer.persistency.EnvelopeVersion;
import i5.las2peer.persistency.MalformedXMLException;
import i5.las2peer.persistency.SharedStorage;
import i5.las2peer.persistency.SharedStorage.STORAGE_MODE;
import i5.las2peer.persistency.StorageArtifactHandler;
import i5.las2peer.persistency.StorageCollisionHandler;
import i5.las2peer.persistency.StorageEnvelopeHandler;
import i5.las2peer.persistency.StorageExceptionHandler;
import i5.las2peer.persistency.StorageStoreResultHandler;
import i5.las2peer.security.AgentContext;
import i5.las2peer.security.AgentImpl;
import i5.las2peer.security.L2pSecurityException;
import i5.las2peer.security.MessageReceiver;
import i5.las2peer.security.UserAgentImpl;
import i5.las2peer.tools.CryptoException;
import i5.las2peer.tools.SerializationException;
import i5.las2peer.tools.SimpleTools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import rice.environment.Environment;
import rice.p2p.commonapi.NodeHandle;
import rice.pastry.PastryNode;
import rice.pastry.socket.internet.InternetPastryNodeFactory;

/**
 * A <a href="http://freepastry.org">FreePastry</a> implementation of a las2peer {@link Node}.
 * 
 * This class is the actual heart of the p2p based network of interacting nodes an agents in the las2peer setting.
 * 
 * The package {@link i5.las2peer.p2p.pastry} provides all necessary helper classes for the integration (and
 * encapsulation) of the FreePastry library.
 */
public class PastryNodeImpl extends Node {

	public static final int DEFAULT_BOOTSTRAP_PORT = 14501;

	private static final L2pLogger logger = L2pLogger.getInstance(PastryNodeImpl.class);

	/**
	 * The PAST_MESSAGE_TIMEOUT defines when a message in a Past (shared storage) operation is considered lost. This
	 * means all other timeouts depend on this value.
	 */
	private static final int PAST_MESSAGE_TIMEOUT = 60000;
	// FIXME the timeouts should be PER STORAGE OPERATION and for the complete fetch or store process, as there might
	// have to be send several messages for a single operation. Their value should be equal to PAST_MESSAGE_TIMEOUT plus
	// a grace value of a few seconds.
	private static final int AGENT_GET_TIMEOUT = 300000;
	private static final int AGENT_STORE_TIMEOUT = 300000;
	private static final int ARTIFACT_GET_TIMEOUT = 300000;
	private static final int ARTIFACT_STORE_TIMEOUT = 300000;
	private static final int HASHED_FETCH_TIMEOUT = 300000;
	private static final int HASHED_STORE_TIMEOUT = 300000;

	private final int pastryPort;
	private final String bootStrap;
	private final STORAGE_MODE storageMode;
	private InetAddress pastryBindAddress; // null = auto detect Internet address
	private ExecutorService threadpool; // gather all threads in node object to minimize idle threads
	private Environment pastryEnvironment;
	private PastryNode pastryNode;
	private NodeApplication application;
	private SharedStorage pastStorage;
	private String storageDir; // null = default chosen by SharedStorage
	private Long nodeIdSeed;

	/**
	 * This constructor is mainly used by the {@link i5.las2peer.testing.TestSuite}, uses a random system defined port
	 * number and sets all parameters for a debugging and testing optimized operation mode.
	 * 
	 * @param bootstrap A bootstrap address that should be used, like hostname:port or <code>null</code> to start a new
	 *            network.
	 * @param storageMode A storage mode to be used by this node, see
	 *            {@link i5.las2peer.persistency.SharedStorage.STORAGE_MODE}.
	 * @param storageDir A directory to persist data to. Only considered in persistent storage mode. Overwrites
	 *            {@link SharedStorage} configurations, which defines the default value in case of <code>null</code>.
	 * @param nodeIdSeed A node id seed to enforce a specific (otherwise random) node id. If <code>null</code>, the node
	 *            id will be random.
	 */
	public PastryNodeImpl(String bootstrap, STORAGE_MODE storageMode, String storageDir, Long nodeIdSeed) {
		this(null, false, InetAddress.getLoopbackAddress(), null, bootstrap, storageMode, storageDir, nodeIdSeed);
	}

	/**
	 * This is the regular constructor used by the {@link i5.las2peer.tools.L2pNodeLauncher}. Its parameters can be set
	 * to start a new network or join an existing Pastry ring.
	 * 
	 * @param classManager A class manager that is used by the node.
	 * @param useMonitoringObserver If true, the node sends monitoring information to the monitoring service.
	 * @param pastryPort A port number the PastryNode should listen to for network communication. <code>null</code>
	 *            means use a random system defined port. Use {@link #getPort()} to retrieve the number.
	 * @param bootstrap A bootstrap address that should be used, like hostname:port or <code>null</code> to start a new
	 *            network.
	 * @param storageMode A storage mode to be used by this node, see
	 *            {@link i5.las2peer.persistency.SharedStorage.STORAGE_MODE}.
	 * @param storageDir A directory to persist data to. Only considered in persistent storage mode. Overwrites
	 *            {@link SharedStorage} configurations, which defines the default value in case of <code>null</code>.
	 * @param nodeIdSeed A node id (random) seed to enforce a specific node id. If <code>null</code>, the node id will
	 *            be random.
	 */
	public PastryNodeImpl(L2pClassManager classManager, boolean useMonitoringObserver, InetAddress pastryBindAddress,
			Integer pastryPort, String bootstrap, STORAGE_MODE storageMode, String storageDir, Long nodeIdSeed) {
		super(classManager, true, useMonitoringObserver);
		this.pastryBindAddress = pastryBindAddress;
		if (pastryPort == null || pastryPort < 1) {
			this.pastryPort = getSystemDefinedPort();
		} else {
			this.pastryPort = pastryPort;
		}
		this.bootStrap = bootstrap;
		this.storageMode = storageMode;
		this.storageDir = storageDir;
		this.nodeIdSeed = nodeIdSeed;
		this.setStatus(NodeStatus.CONFIGURED);
	}

	/**
	 * access to the underlying pastry node
	 * 
	 * @return the pastry node representing this las2peer node
	 */
	public PastryNode getPastryNode() {
		return pastryNode;
	}

	/**
	 * generate a collection of InetSocketAddresses from the given bootstrap string
	 * 
	 * @return collection of InetSocketAddresses from the given bootstrap string
	 */
	private Collection<InetSocketAddress> getBootstrapAddresses() {
		Vector<InetSocketAddress> result = new Vector<>();
		if (bootStrap == null || bootStrap.isEmpty()) {
			return result;
		}
		String[] addresses = bootStrap.split(",");
		for (String address : addresses) {
			String[] hostAndPort = address.split(":");
			int port = DEFAULT_BOOTSTRAP_PORT;
			if (hostAndPort.length == 2) {
				port = Integer.parseInt(hostAndPort[1]);
			}
			try {
				result.add(new InetSocketAddress(InetAddress.getByName(hostAndPort[0]), port));
			} catch (UnknownHostException e) {
				// TODO this should be handled earlier
				if (address.equals("-")) {
					System.out.println("Starting new network..");
				} else {
					System.err.println("Cannot resolve address for: " + address + "\n Starting new network..");
				}
			}
		}
		return result;
	}

	/**
	 * Setup all pastry applications to run on this node.
	 * 
	 * This will be
	 * <ul>
	 * <li>the application for message passing {@link NodeApplication}</li>
	 * <li>a Past DHT storage from Freepastry</li>
	 * </ul>
	 * 
	 * For the past DHT either a memory mode or a disk persistence mode are selected based on {@link STORAGE_MODE}
	 * 
	 * @throws EnvelopeException
	 */
	private void setupPastryApplications() throws EnvelopeException {
		threadpool = Executors.newCachedThreadPool();
		application = new NodeApplication(this);
		pastStorage = new SharedStorage(pastryNode, storageMode, threadpool, storageDir);
		// add past storage as network repository
		getBaseClassLoader().addRepository(new SharedStorageRepository(this));
	}

	/**
	 * start this node
	 */
	@Override
	protected void launchSub() throws NodeException {
		try {
			setStatus(NodeStatus.STARTING);

			setupPastryEnvironment();

			if (nodeIdSeed == null) {
				// auto generate node id seed from port
				nodeIdSeed = Long.valueOf(getPort());
			}
			InternetPastryNodeFactory factory = new InternetPastryNodeFactory(new L2pNodeIdFactory(nodeIdSeed),
					pastryBindAddress, pastryPort, pastryEnvironment, null, null, null);
			pastryNode = factory.newNode();

			setupPastryApplications();

			Collection<InetSocketAddress> boot = getBootstrapAddresses();
			if (boot == null || boot.isEmpty()) {
				logger.info("Start new las2peer network ...");
			} else {
				logger.info("Bootstrapping to " + SimpleTools.join(boot, ", ") + " ...");
			}
			pastryNode.boot(boot);

			synchronized (pastryNode) {
				while (!pastryNode.isReady() && !pastryNode.joinFailed()) {
					// delay so we don't busy-wait
					pastryNode.wait(500);

					// abort if can't join
					if (pastryNode.joinFailed()) {
						throw new NodeException("Could not join the FreePastry ring.  Reason:"
								+ pastryNode.joinFailedReason());
					}
				}
			}

			logger.info("Node " + pastryNode.getId().toStringFull() + " started");

			setStatus(NodeStatus.RUNNING);

			registerShutdownMethod();

		} catch (IOException e) {
			throw new NodeException("IOException while joining pastry ring", e);
		} catch (EnvelopeException e) {
			throw new NodeException("Shared storage exception while joining pastry ring", e);
		} catch (InterruptedException e) {
			throw new NodeException("Interrupted while joining pastry ring!", e);
		} catch (IllegalStateException e) {
			throw new NodeException("Unable to open Netwock socket - is the port already in use?", e);
		}
	}

	/**
	 * setup pastry environment settings
	 */
	private void setupPastryEnvironment() {
		pastryEnvironment = new Environment();
		String[] configFiles = new String[] { "etc/pastry.properties", "config/pastry.properties",
				"properties/pastry.properties" };
		String found = null;
		for (String filename : configFiles) {
			try {
				if (new File(filename).exists()) {
					found = filename;
					break;
				}
			} catch (Exception e) {
				logger.log(Level.FINER, "Exception while checking for config file '" + filename + "'", e);
			}
		}
		Hashtable<String, String> properties = new Hashtable<>();
		if (found != null) {
			System.out.println("Using pastry property file " + found);
			try {
				Properties props = new Properties();
				props.load(new FileInputStream(found));

				for (Object propname : props.keySet()) {
					properties.put((String) propname, (String) props.get(propname));
				}
			} catch (FileNotFoundException e) {
				System.err.println("Unable to open property file " + found);
			} catch (IOException e) {
				System.err.println("Error opening property file " + found + ": " + e.getMessage());
			}
		} else {
			logger.fine("No pastry property file found - using default values");
		}
		if (!properties.containsKey("nat_search_policy")) {
			properties.put("nat_search_policy", "never");
		}
		if (!properties.containsKey("firewall_test_policy")) {
			properties.put("firewall_test_policy", "never");
		}
		if (!properties.containsKey("nat_network_prefixes")) {
			properties.put("nat_network_prefixes", "127.0.0.1;10.;192.168.;");
		}
		if (pastryBindAddress != null && pastryBindAddress.isLoopbackAddress()) {
			properties.put("allow_loopback_address", "1");
		}
		if (!properties.containsKey("p2p_past_messageTimeout")) {
			properties.put("p2p_past_messageTimeout", Integer.toString(PAST_MESSAGE_TIMEOUT));
		}
		if (!properties.containsKey("pastry_socket_known_network_address")) {
			if (!properties.containsKey("pastry_socket_known_network_address_port")) {
				properties.put("pastry_socket_known_network_address_port", "80");
			}
		}
		if (!properties.containsKey("nat_search_policy")) {
			properties.put("nat_search_policy", "never");
		}
		for (String prop : properties.keySet()) {
			pastryEnvironment.getParameters().setString(prop, properties.get(prop));
			logger.fine("setting: " + prop + ": '" + properties.get(prop) + "'");
		}
	}

	public static int getSystemDefinedPort() {
		try {
			ServerSocket tmpSocket = new ServerSocket(0);
			tmpSocket.close();
			return tmpSocket.getLocalPort();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * register node shutdown as JVM shutdown method
	 */
	private void registerShutdownMethod() {
		final PastryNodeImpl self = this;

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				self.shutDown();
			}
		});
	}

	@Override
	public synchronized void shutDown() {
		this.setStatus(NodeStatus.CLOSING);
		super.shutDown();
		if (threadpool != null) {
			// destroy pending jobs first, because they miss the node the most
			threadpool.shutdownNow();
		}
		if (pastryNode != null) {
			pastryNode.destroy();
			pastryNode = null;
		}
		if (pastryEnvironment != null) {
			pastryEnvironment.destroy();
			pastryEnvironment = null;
		}
		this.setStatus(NodeStatus.CLOSED);
	}

	@Override
	public void registerReceiver(MessageReceiver receiver) throws AgentAlreadyRegisteredException,
			L2pSecurityException, AgentException {

		synchronized (this) {
			super.registerReceiver(receiver);
			application.registerAgentTopic(receiver);

			// Observer is called in superclass!
		}

	}

	@Override
	public void unregisterReceiver(MessageReceiver receiver) throws AgentNotRegisteredException, NodeException {
		synchronized (this) {
			application.unregisterAgentTopic(receiver.getResponsibleForAgentSafeId());
			super.unregisterReceiver(receiver);
		}
	}

	@Override
	public void registerReceiverToTopic(MessageReceiver receiver, long topic) throws AgentNotRegisteredException {
		synchronized (this) {
			super.registerReceiverToTopic(receiver, topic);
			application.registerTopic(topic);
		}
	}

	@Override
	public void unregisterReceiverFromTopic(MessageReceiver receiver, long topic) throws NodeException {
		synchronized (this) {
			super.unregisterReceiverFromTopic(receiver, topic);

			if (!super.hasTopic(topic)) {
				application.unregisterTopic(topic);
			}
		}
	}

	@Override
	public void sendMessage(Message message, MessageResultListener listener, SendMode mode) {
		// TODO: use mode?!?!
		observerNotice(MonitoringEvent.MESSAGE_SENDING, pastryNode, message.getSenderId(), null,
				message.getRecipientId(), "broadcasting");

		registerAnswerListener(message.getId(), listener);

		application.sendMessage(message);
	}

	@Override
	public void sendMessage(Message message, Object atNodeId, MessageResultListener listener)
			throws NodeNotFoundException {

		if (!(atNodeId instanceof NodeHandle)) {
			String addition = "(null)";
			if (atNodeId != null) {
				addition = atNodeId.getClass().toString();
			}
			IllegalArgumentException e = new IllegalArgumentException(
					"node id in pastry nets has to be a NodeHandle instance but is " + addition);
			e.printStackTrace();
			throw e;
		}

		observerNotice(MonitoringEvent.MESSAGE_SENDING, pastryNode, message.getSenderId(), atNodeId,
				message.getRecipientId(), "");

		registerAnswerListener(message.getId(), listener);

		try {
			application.sendMessage(new MessageEnvelope(pastryNode.getLocalHandle(), message), (NodeHandle) atNodeId);
		} catch (MalformedXMLException e) {
			logger.log(Level.SEVERE, "Can't read message XML", e);
			observerNotice(MonitoringEvent.MESSAGE_FAILED, pastryNode, message.getSenderId(), atNodeId,
					message.getRecipientId(), "XML exception!");
		} catch (MessageException e) {
			logger.log(Level.SEVERE, "Could not send message", e);
			observerNotice(MonitoringEvent.MESSAGE_FAILED, pastryNode, message.getSenderId(), atNodeId,
					message.getRecipientId(), "Message exception!");
		}
	}

	/**
	 * @deprecated Use {@link #fetchEnvelope(String)} instead
	 */
	@Deprecated
	@Override
	public EnvelopeVersion fetchArtifact(long id) throws EnvelopeNotFoundException, EnvelopeException {
		return fetchEnvelope(Long.toString(id));
	}

	/**
	 * @deprecated Use {@link #fetchEnvelope(String)} instead
	 */
	@Deprecated
	@Override
	public EnvelopeVersion fetchArtifact(String identifier) throws EnvelopeNotFoundException, EnvelopeException {
		return fetchEnvelope(identifier);
	}

	/**
	 * @deprecated Use {@link #storeEnvelope(EnvelopeVersion, AgentImpl)} instead
	 */
	@Deprecated
	@Override
	public void storeArtifact(EnvelopeVersion envelope) throws EnvelopeException {
		storeEnvelope(envelope, AgentContext.getCurrent().getMainAgent());
	}

	/**
	 * @deprecated Use {@link #removeEnvelope(String)} instead
	 */
	@Deprecated
	@Override
	public void removeArtifact(long id, byte[] signature) throws EnvelopeNotFoundException, EnvelopeException {
		removeEnvelope(Long.toString(id));
	}

	@Override
	public Object[] findRegisteredAgent(String agentId, int hintOfExpectedCount) throws AgentNotRegisteredException {
		observerNotice(MonitoringEvent.AGENT_SEARCH_STARTED, pastryNode, agentId, null, (String) null, "");
		return application.searchAgent(agentId, hintOfExpectedCount).toArray();
	}

	/**
	 * provides access to the underlying pastry application mostly for testing purposes
	 * 
	 * @return the pastry node application of this pastry node
	 */
	public NodeApplication getApplication() {
		return application;
	}

	@Override
	public AgentImpl getAgent(String id) throws AgentNotFoundException, AgentException {
		// no caching here, because agents may have changed in the network
		observerNotice(MonitoringEvent.AGENT_GET_STARTED, pastryNode, id, null, (String) null, "");
		try {
			AgentImpl agentFromNet = null;
			AgentImpl anonymous = getAnonymous();
			// TODO use isAnonymous, special ID or Classing for identification
			if (id.equalsIgnoreCase(anonymous.getSafeId())) {
				agentFromNet = anonymous;
			} else {
				EnvelopeVersion agentEnvelope = pastStorage.fetchEnvelope(EnvelopeVersion.getAgentIdentifier(id),
						AGENT_GET_TIMEOUT);
				agentFromNet = AgentImpl.createFromXml((String) agentEnvelope.getContent());
			}
			observerNotice(MonitoringEvent.AGENT_GET_SUCCESS, pastryNode, id, null, (String) null, "");
			return agentFromNet;
		} catch (EnvelopeNotFoundException e) {
			observerNotice(MonitoringEvent.AGENT_GET_FAILED, pastryNode, id, null, (String) null, "");
			throw new AgentNotFoundException("Agent " + id + " not found in storage", e);
		} catch (EnvelopeException | MalformedXMLException | SerializationException | L2pSecurityException
				| CryptoException e) {
			observerNotice(MonitoringEvent.AGENT_GET_FAILED, pastryNode, id, null, (String) null, "");
			throw new AgentException("Unable to retrieve Agent " + id + " from past storage", e);
		}
	}

	@Override
	public void storeAgent(AgentImpl agent) throws L2pSecurityException, AgentException {
		if (agent.isLocked()) {
			throw new L2pSecurityException("You have to unlock the agent before storage!");
			// because the agent has to sign itself
		}
		// TODO check if anonymous should be stored and deny
		observerNotice(MonitoringEvent.AGENT_UPLOAD_STARTED, pastryNode, agent, "");
		try {
			EnvelopeVersion agentEnvelope = null;
			try {
				agentEnvelope = pastStorage.fetchEnvelope(EnvelopeVersion.getAgentIdentifier(agent.getSafeId()),
						AGENT_GET_TIMEOUT);
				agentEnvelope = pastStorage.createUnencryptedEnvelope(agentEnvelope, agent.toXmlString());
			} catch (EnvelopeNotFoundException e) {
				agentEnvelope = pastStorage.createUnencryptedEnvelope(
						EnvelopeVersion.getAgentIdentifier(agent.getSafeId()), agent.toXmlString());
			}
			pastStorage.storeEnvelope(agentEnvelope, agent, AGENT_STORE_TIMEOUT);
			if (agent instanceof UserAgentImpl) {
				getUserManager().registerUserAgent((UserAgentImpl) agent);
			}
			observerNotice(MonitoringEvent.AGENT_UPLOAD_SUCCESS, pastryNode, agent, "");
		} catch (CryptoException | SerializationException | EnvelopeException e) {
			observerNotice(MonitoringEvent.AGENT_UPLOAD_FAILED, pastryNode, agent, "Got interrupted!");
			throw new AgentException("Storage has been interrupted", e);
		}
	}

	/**
	 * @deprecated Use {@link #storeAgent(AgentImpl)} instead
	 */
	@Deprecated
	@Override
	public void updateAgent(AgentImpl agent) throws AgentException, L2pSecurityException, EnvelopeException {
		storeAgent(agent);
	}

	/**
	 * get the identifier of this node (string representation of the pastry node)
	 * 
	 * @return complete identifier of this pastry node as String
	 */
	@Override
	public Serializable getNodeId() {
		if (pastryNode == null) {
			return null;
		} else {
			return pastryNode.getLocalNodeHandle();
		}
	}

	public InetAddress getBindAddress() {
		return pastryBindAddress;
	}

	public int getPort() {
		return pastryPort;
	}

	@Override
	public Object[] getOtherKnownNodes() {
		return pastryNode.getLeafSet().getUniqueSet().toArray();
	}

	@Override
	public NodeInformation getNodeInformation(Object nodeId) throws NodeNotFoundException {
		if (!(nodeId instanceof NodeHandle)) {
			throw new NodeNotFoundException("Given node id is not a pastry Node Handle!");
		}
		return application.getNodeInformation((NodeHandle) nodeId);
	}

	public long getLocalStorageSize() {
		if (pastStorage == null) {
			throw new IllegalStateException("Storage not yet initialized");
		}
		return pastStorage.getLocalSize();
	}

	public long getLocalMaxStorageSize() {
		if (pastStorage == null) {
			throw new IllegalStateException("Storage not yet initialized");
		}
		return pastStorage.getLocalMaxSize();
	}

	@Override
	public void storeEnvelope(EnvelopeVersion envelope, AgentImpl author) throws EnvelopeException {
		storeEnvelope(envelope, author, ARTIFACT_STORE_TIMEOUT);
	}

	@Override
	public EnvelopeVersion fetchEnvelope(String identifier) throws EnvelopeNotFoundException, EnvelopeException {
		return fetchEnvelope(identifier, ARTIFACT_GET_TIMEOUT);
	}

	@Override
	public EnvelopeVersion createEnvelope(String identifier, Serializable content, AgentImpl... reader)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return pastStorage.createEnvelope(identifier, content, reader);
	}

	@Override
	public EnvelopeVersion createEnvelope(String identifier, Serializable content, Collection<?> readers)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return pastStorage.createEnvelope(identifier, content, readers);
	}

	@Override
	public EnvelopeVersion createEnvelope(EnvelopeVersion previousVersion, Serializable content)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return pastStorage.createEnvelope(previousVersion, content);
	}

	@Override
	public EnvelopeVersion createEnvelope(EnvelopeVersion previousVersion, Serializable content, AgentImpl... reader)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return pastStorage.createEnvelope(previousVersion, content, reader);
	}

	@Override
	public EnvelopeVersion createEnvelope(EnvelopeVersion previousVersion, Serializable content, Collection<?> readers)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return pastStorage.createEnvelope(previousVersion, content, readers);
	}

	@Override
	public EnvelopeVersion createUnencryptedEnvelope(String identifier, Serializable content)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return pastStorage.createUnencryptedEnvelope(identifier, content);
	}

	@Override
	public EnvelopeVersion createUnencryptedEnvelope(EnvelopeVersion previousVersion, Serializable content)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return pastStorage.createUnencryptedEnvelope(previousVersion, content);
	}

	@Override
	public void storeEnvelope(EnvelopeVersion envelope, AgentImpl author, long timeoutMs) throws EnvelopeException {
		try {
			pastStorage.storeEnvelope(envelope, author, timeoutMs);
		} catch (EnvelopeException e) {
			observerNotice(MonitoringEvent.ARTIFACT_UPLOAD_FAILED, pastryNode,
					"Storage error for Artifact " + envelope.getIdentifier());
			throw e; // transparent exception forwarding
		}
		observerNotice(MonitoringEvent.ARTIFACT_ADDED, pastryNode, envelope.getIdentifier());
	}

	@Override
	public void storeEnvelopeAsync(EnvelopeVersion envelope, AgentImpl author, StorageStoreResultHandler resultHandler,
			StorageCollisionHandler collisionHandler, StorageExceptionHandler exceptionHandler) {
		pastStorage.storeEnvelopeAsync(envelope, author, new StorageStoreResultHandler() {
			@Override
			public void onResult(Serializable serializable, int successfulOperations) {
				observerNotice(MonitoringEvent.ARTIFACT_ADDED, pastryNode, envelope.getIdentifier());
				if (resultHandler != null) {
					resultHandler.onResult(serializable, successfulOperations);
				}
			}
		}, collisionHandler, new StorageExceptionHandler() {
			@Override
			public void onException(Exception e) {
				observerNotice(MonitoringEvent.ARTIFACT_UPLOAD_FAILED, pastryNode, "Storage error for Artifact "
						+ envelope.getIdentifier());
				if (exceptionHandler != null) {
					exceptionHandler.onException(e);
				}
			}
		});
	}

	@Override
	public EnvelopeVersion fetchEnvelope(String identifier, long timeoutMs) throws EnvelopeNotFoundException,
			EnvelopeException {
		if (pastStorage == null) {
			throw new IllegalStateException(
					"Past storage not initialized! You can fetch artifacts only from running nodes!");
		}
		observerNotice(MonitoringEvent.ARTIFACT_FETCH_STARTED, pastryNode, identifier);
		try {
			EnvelopeVersion contentFromNet = pastStorage.fetchEnvelope(identifier, timeoutMs);
			observerNotice(MonitoringEvent.ARTIFACT_RECEIVED, pastryNode, identifier);
			return contentFromNet;
		} catch (EnvelopeNotFoundException e) {
			observerNotice(MonitoringEvent.ARTIFACT_FETCH_FAILED, pastryNode, identifier);
			throw e; // transparent exception forwarding
		} catch (Exception e) {
			observerNotice(MonitoringEvent.ARTIFACT_FETCH_FAILED, pastryNode, identifier);
			throw new EnvelopeException("Unable to retrieve Artifact from past storage", e);
		}
	}

	@Override
	public void fetchEnvelopeAsync(String identifier, StorageEnvelopeHandler envelopeHandler,
			StorageExceptionHandler exceptionHandler) {
		if (getStatus() != NodeStatus.RUNNING) {
			throw new IllegalStateException("You can fetch artifacts only from running nodes!");
		}
		observerNotice(MonitoringEvent.ARTIFACT_FETCH_STARTED, pastryNode, identifier);
		pastStorage.fetchEnvelopeAsync(identifier, new StorageEnvelopeHandler() {
			@Override
			public void onEnvelopeReceived(EnvelopeVersion result) {
				observerNotice(MonitoringEvent.ARTIFACT_RECEIVED, pastryNode, identifier);
				if (envelopeHandler != null) {
					envelopeHandler.onEnvelopeReceived(result);
				}
			}
		}, new StorageExceptionHandler() {
			@Override
			public void onException(Exception e) {
				observerNotice(MonitoringEvent.ARTIFACT_FETCH_FAILED, pastryNode, identifier);
				if (exceptionHandler != null) {
					exceptionHandler.onException(e);
				}
			}
		});
	}

	@Override
	public void removeEnvelope(String identifier) throws EnvelopeNotFoundException, EnvelopeException {
		pastStorage.removeEnvelope(identifier);
	}

	public void storeHashedContentAsync(byte[] content, StorageStoreResultHandler resultHandler,
			StorageExceptionHandler exceptionHandler) {
		pastStorage.storeHashedContentAsync(content, resultHandler, exceptionHandler);
	}

	public void storeHashedContent(byte[] content) throws EnvelopeException {
		storeHashedContent(content, HASHED_STORE_TIMEOUT);
	}

	public void storeHashedContent(byte[] content, long timeoutMs) throws EnvelopeException {
		pastStorage.storeHashedContent(content, timeoutMs);
	}

	public void fetchHashedContentAsync(byte[] hash, StorageArtifactHandler artifactHandler,
			StorageExceptionHandler exceptionHandler) {
		pastStorage.fetchHashedContentAsync(hash, artifactHandler, exceptionHandler);
	}

	public byte[] fetchHashedContent(byte[] hash) throws EnvelopeException {
		return fetchHashedContent(hash, HASHED_FETCH_TIMEOUT);
	}

	public byte[] fetchHashedContent(byte[] hash, long timeoutMs) throws EnvelopeException {
		return pastStorage.fetchHashedContent(hash, timeoutMs);
	}

}
