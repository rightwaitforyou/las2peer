package i5.las2peer.testing;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

import org.junit.Assert;

import i5.las2peer.p2p.PastryNodeImpl;
import i5.las2peer.persistency.SharedStorage.STORAGE_MODE;

/**
 * This class provides methods for developers to simplify JUnit test creation.
 *
 */
public class TestSuite {

	public static final String TEST_STORAGE_DIR = "tmp/test-storage/";

	/**
	 * This method starts a network consisting of the given number of nodes. The nodes should be used for testing only
	 * and do NOT persist data in the filesystem.
	 *
	 * @param numOfNodes The number of nodes that should be in the network.
	 * @return Returns a list with all nodes from the network.
	 * @throws Exception If an error occurs.
	 */
	public static ArrayList<PastryNodeImpl> launchNetwork(int numOfNodes) throws Exception {
		return launchNetwork(numOfNodes, STORAGE_MODE.MEMORY);
	}

	/**
	 * This method starts a network consisting of the given number of nodes.
	 *
	 * @param numOfNodes The number of nodes that should be in the network.
	 * @return Returns a list with all nodes from the network.
	 * @throws Exception If an error occurs.
	 */
	public static ArrayList<PastryNodeImpl> launchNetwork(int numOfNodes, STORAGE_MODE storageMode) throws Exception {
		wipeTestStorage();
		ArrayList<PastryNodeImpl> result = new ArrayList<>();
		// launch bootstrap node
		PastryNodeImpl bootstrapNode = new PastryNodeImpl(null, storageMode, TEST_STORAGE_DIR, 0L);
		bootstrapNode.launch();
		int bootstrapPort = bootstrapNode.getPort();
		Assert.assertNotEquals(bootstrapPort, 0);
		result.add(bootstrapNode);
		System.out.println("bootstrap node launched with id " + bootstrapNode.getNodeId());
		// add more nodes
		for (int num = 1; num <= numOfNodes - 1; num++) {
			PastryNodeImpl node2 = addNode(bootstrapPort + num, bootstrapPort, storageMode, (long) num);
			result.add(node2);
			System.out.println("network node launched with id " + bootstrapNode.getNodeId());
		}
		return result;
	}

	public static PastryNodeImpl addNode(int port, int bootstrapPort, STORAGE_MODE storageMode, Long nodeIdSeed)
			throws Exception {
		PastryNodeImpl node = new PastryNodeImpl(InetAddress.getLocalHost().getHostAddress() + ":" + bootstrapPort,
				storageMode, TEST_STORAGE_DIR, nodeIdSeed);
		node.launch();
		return node;
	}

	public static void wipeTestStorage() throws IOException {
		// delete old test-storage data
		try {
			Path directory = Paths.get(TEST_STORAGE_DIR);
			Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
			System.out.println("wiped " + TEST_STORAGE_DIR);
		} catch (NoSuchFileException e) {
			// no test data to delete
		}
	}

	/**
	 * self test
	 * 
	 * @param args ignored
	 */
	public static void main(String[] args) {
		try {
			System.out.println("starting network");
			ArrayList<PastryNodeImpl> nodes = launchNetwork(5);
			System.out.println("network start complete");
			Thread.sleep(1000);
			System.out.println("shutting down network");
			for (PastryNodeImpl node : nodes) {
				node.shutDown();
			}
			System.out.println("self test complete");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
