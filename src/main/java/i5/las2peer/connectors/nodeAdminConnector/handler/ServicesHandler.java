package i5.las2peer.connectors.nodeAdminConnector.handler;

import java.io.InputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.CookieParam;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.glassfish.jersey.media.multipart.FormDataParam;

import i5.las2peer.api.persistency.EnvelopeAlreadyExistsException;
import i5.las2peer.api.persistency.EnvelopeNotFoundException;
import i5.las2peer.classLoaders.L2pClassManager;
import i5.las2peer.classLoaders.libraries.SharedStorageRepository;
import i5.las2peer.connectors.nodeAdminConnector.AgentSession;
import i5.las2peer.connectors.nodeAdminConnector.NodeAdminConnector;
import i5.las2peer.p2p.Node;
import i5.las2peer.persistency.EnvelopeVersion;
import i5.las2peer.tools.CryptoTools;
import i5.las2peer.tools.PackageUploader;
import i5.las2peer.tools.PackageUploader.ServiceVersionList;
import i5.las2peer.tools.ServicePackageException;
import i5.las2peer.tools.SimpleTools;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

@Path("/services")
public class ServicesHandler extends AbstractHandler {

	public ServicesHandler(NodeAdminConnector connector) {
		super(connector);
	}

	@GET
	@Path("/search")
	@Produces(MediaType.APPLICATION_JSON)
	public Response handleSearchService(@HeaderParam("Host") String hostHeader,
			@QueryParam("searchname") String searchName) {
		JSONObject result = new JSONObject();
		try {
			JSONArray instances = new JSONArray();
			if (searchName == null || searchName.isEmpty()) {
				// iterate local services
				List<String> serviceNames = node.getNodeServiceCache().getLocalServiceNames();
				for (String serviceName : serviceNames) {
					// add service versions from network
					instances.addAll(getNetworkServices(node, hostHeader, serviceName));
				}
			} else {
				// search for service version in network
				instances.addAll(getNetworkServices(node, hostHeader, searchName));
			}
			result.put("instances", instances);
		} catch (EnvelopeNotFoundException | IllegalArgumentException e) {
			result.put("msg", "'" + searchName + "' not found");
		} catch (Exception e) {
			result.put("msg", e.toString());
		}
		return Response.ok(result.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	private JSONArray getNetworkServices(Node node, String hostHeader, String searchName) throws Exception {
		JSONArray result = new JSONArray();
		String libName = L2pClassManager.getPackageName(searchName);
		String libId = SharedStorageRepository.getLibraryVersionsEnvelopeIdentifier(libName);
		EnvelopeVersion networkVersions = node.fetchEnvelope(libId);
		Serializable content = networkVersions.getContent();
		if (content instanceof ServiceVersionList) {
			ServiceVersionList serviceversions = (ServiceVersionList) content;
			for (String version : serviceversions) {
				JSONObject jsonObject = new JSONObject();
				jsonObject.put("name", searchName);
				jsonObject.put("version", version);
				// use host header, so browsers do not block subsequent ajax requests to an unknown host
				jsonObject.put("swagger", "https://" + hostHeader + RMIHandler.RMI_PATH + "/" + searchName + "/"
						+ version + "/swagger.json");
				result.add(jsonObject);
			}
		} else {
			throw new ServicePackageException("Invalid version envelope expected " + List.class.getCanonicalName()
					+ " but envelope contains " + content.getClass().getCanonicalName());
		}
		return result;
	}

	@POST
	@Path("/upload")
	@Produces(MediaType.APPLICATION_JSON)
	public Response handleServicePackageUpload(@CookieParam(NodeAdminConnector.COOKIE_SESSIONID_KEY) String sessionId,
			@FormDataParam("jarfile") InputStream jarfile) throws Exception {
		AgentSession session = connector.getSessionById(sessionId);
		if (session == null) {
			throw new BadRequestException("You have to be logged in to upload");
		}
		if (jarfile == null) {
			throw new BadRequestException("No jar file provided");
		}
		// create jar from inputstream
		JarInputStream jarStream = new JarInputStream(jarfile);
		// read general service information from jar manifest
		Manifest manifest = jarStream.getManifest();
		if (manifest == null) {
			jarStream.close();
			throw new BadRequestException("Service jar package contains no manifest file");
		}
		String serviceName = manifest.getMainAttributes().getValue("las2peer-service-name");
		String serviceVersion = manifest.getMainAttributes().getValue("las2peer-service-version");
		// read files from jar and generate hashes
		HashMap<String, byte[]> depHashes = new HashMap<>();
		HashMap<String, byte[]> jarFiles = new HashMap<>();
		JarEntry entry = null;
		while ((entry = jarStream.getNextJarEntry()) != null) {
			if (!entry.isDirectory()) {
				byte[] bytes = SimpleTools.toByteArray(jarStream);
				jarStream.closeEntry();
				byte[] hash = CryptoTools.getSecureHash(bytes);
				String filename = entry.getName();
				depHashes.put(filename, hash);
				jarFiles.put(filename, bytes);
			}
		}
		jarStream.close();
		try {
			PackageUploader.uploadServicePackage(node, serviceName, serviceVersion, depHashes, jarFiles,
					session.getAgent());
			JSONObject json = new JSONObject();
			json.put("code", Status.OK.getStatusCode());
			json.put("text", Status.OK.getStatusCode() + " - Service package upload successful");
			json.put("msg", "Service package upload successful");
			return Response.ok(json.toJSONString(), MediaType.APPLICATION_JSON).build();
		} catch (EnvelopeAlreadyExistsException e) {
			throw new BadRequestException("Version is already known in the network. To update increase version number",
					e);
		} catch (ServicePackageException e) {
			e.printStackTrace();
			throw new BadRequestException("Service package upload failed", e);
		}
	}

}