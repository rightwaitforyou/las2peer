package i5.las2peer.api.security;

/**
 * Represents a group of agents that is an agent itself.
 * 
 * Note that changes are only applied when the agent is stored.
 * 
 * A GroupAgent has two states: a locked state and an unlocked state. Only unlocked agents can be altered and stored.
 *
 */
public interface GroupAgent extends Agent {

	/**
	 * Adds a member to the group.
	 * 
	 * @param agent The member to add.
	 */
	public void addMember(Agent agent);

	/**
	 * Revokes a group membership. This means that the given agent will not have access to any future updates to agents
	 * and contents this group has access to throgh this group. However, existing content may still be available to the
	 * revoked member.
	 * 
	 * Ideally, a new key pair for this group will be generated whenever a member is removed.
	 * 
	 * @param agent The member to remove from the group.
	 */
	public void revokeMember(Agent agent);

	/**
	 * Checks if the given agent is a member of this group.
	 * 
	 * @param agent The agent whose membership has to be checked.
	 * @return true if the given agent is a member of this group.
	 */
	public boolean hasMember(Agent agent);

	/**
	 * Checks if the given agent is a member of this group.
	 * 
	 * @param agentId The id of the agent whose membership has to be checked.
	 * @return true if the given agent is a member of this group.
	 */
	public boolean hasMember(String agentId);

	/**
	 * Unlock this group using a given agent. Will not work for transitive memberships.
	 * 
	 * @param agent A direct member of this group.
	 * @throws AgentAccessDeniedException If the given agent is not a direct member of this group.
	 * @throws AgentOperationFailedException If the agent is corrupted.
	 */
	public void unlock(Agent agent) throws AgentAccessDeniedException, AgentOperationFailedException;
	
	/**
	 * Get the number of direct group members.
	 * 
	 * @return The group size.
	 */
	public int getSize();
	
	/**
	 * Get a list of direct group members.
	 * 
	 * @return A list of ids of group members.
	 */
	public String[] getMemberList();

	// TODO add admins to GroupAgents
	/*
	void addAdmin(Agent agent);
	void revokeAdmin(Agent agent);
	boolean hasAdmin(Agent agent);
	*/
}