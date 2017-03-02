package i5.las2peer.security;

import i5.las2peer.api.security.AgentException;

public class UserAgentException extends AgentException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7423250266497359703L;

	public UserAgentException() {
		super("");
	}

	public UserAgentException(String message) {
		super(message);
	}

}
