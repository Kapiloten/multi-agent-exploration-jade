package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.behaviours.OneShotBehaviour;

public class ExplorationBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;

    public ExplorationBehaviour(final AbstractDedaleAgent myAgent) {
        super(myAgent);
    }

    @Override
    public void action() {
        FSMExploAgent agent = (FSMExploAgent) this.myAgent;
        Location loc = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
        if (loc == null) return;
        String myPosition = loc.getLocationId();
        agent.pruneInvalidInferredGolemsAt(myPosition);
        String lastPosition = agent.getLastPosition();
        String nextNode = null;

        try { Thread.sleep(800); } catch (InterruptedException e) {}

        if (agent.getMyMap() == null) {
            agent.initiateMyMap();
            agent.setWait(1);
            agent.setGetoutCnt(0);
        }

        // Mark current node as closed (explored)
        agent.myMapAddNewNode(myPosition);
        agent.getMyMap().addNode(myPosition, MapAttribute.closed);

        // Observe surroundings
        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();
        Set<String> occupiedObservedNodes = getOccupiedObservedNodes(lobs);
        Set<String> knownAgentPositions = agent.getKnownAgentPositions();
        List<String> nodeStench = getStenchNodes(lobs);
        for (Couple<Location, List<Couple<Observation, String>>> entry : lobs) {
            String nodeId = entry.getLeft().getLocationId();
            if (!myPosition.equals(nodeId)) {
                boolean isNew = agent.myMapAddNewNode(nodeId);
                agent.myMapAddEdge(myPosition, nodeId);
                if (nextNode == null && isNew && isFreeNode(nodeId, myPosition, occupiedObservedNodes, agent)) {
                    nextNode = nodeId;
                }
            }
        }

        updateVisibleGolems(lobs, agent);
        updateOwnStench(agent, myPosition, nodeStench);

        // Check if exploration is complete
        if (agent.getGetoutCnt() >= 10 || !agent.getMyMap().hasOpenNode()) {
            agent.setMode(FSMExploAgent.MODE_HUNT);
            double cc = agent.getMyMap().checkTypeGraph();
            agent.setStyle((Double.isNaN(cc) || cc < 0.05) ? 0 : 1);
            System.out.println(agent.getLocalName() + " switching to HUNT mode. Style: " + (agent.getStyle() == 0 ? "Tree" : "Graph"));
            return;
        }

        // Destination management
        if (myPosition.equals(agent.getDestination())) {
            agent.setDest_wumpusfound(false);
        }

        // Movement decision
        if (lastPosition.equals(myPosition) && knownAgentPositions.contains(agent.getNextDest())) {
            String far = null;
            int attempts = 0;
            while (far == null || far.equals(myPosition) ||
                    agent.getMyMap().getShortestPath(myPosition, far) == null ||
                    agent.getMyMap().getShortestPath(myPosition, far).contains(agent.getNextDest())) {
                far = agent.getMyMap().getRandomNode();
                attempts++;
                if (attempts >= 50) {
                    far = null;
                    break;
                }
            }
            if (far != null) {
                List<String> path = agent.getMyMap().getShortestPath(myPosition, far);
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
                if (new Random().nextDouble() >= 0.5) nextNode = agent.getNextDest();
            }
        } else if (agent.isDest_wumpusfound() && agent.getDestination() != null) {
            List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
            if (path != null && !path.isEmpty()) nextNode = path.get(0);
        } else if (lastPosition.equals(myPosition) && !knownAgentPositions.contains(agent.getNextDest()) &&
                !myPosition.equals(agent.getNextDest())) {
            agent.increaseWumpusCnt();
            nextNode = agent.getNextDest();
        } else {
            agent.setWumpusCnt(0);

            if (nextNode == null && agent.getMyMap().hasOpenNode()) {
                List<String> path = agent.myMapShortestPathToClosestOpenNode(myPosition, new ArrayList<>(knownAgentPositions));
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }

            if (nextNode == null) agent.increaseGetoutCnt();
            else agent.setGetoutCnt(0);
        }

        if (!isFreeNode(nextNode, myPosition, occupiedObservedNodes, agent)) {
            nextNode = chooseFreeObservedNeighbor(lobs, myPosition, lastPosition, occupiedObservedNodes, agent);
        }

        if (agent.getWait() > 0) agent.decreaseWait();
        agent.setLastPosition(myPosition);
        agent.setNextDest(nextNode);
        if (nextNode != null) {
            boolean moved = ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            System.out.println(agent.getLocalName() + " [EXPLORE] moving to " + nextNode + " success=" + moved);
            if (moved) {
                agent.clearFailedStenchMove(nextNode);
                agent.pruneInvalidInferredGolemsAt(nextNode);
            } else {
                handleFailedStenchMove(agent, nextNode, nodeStench);
                String fallback = chooseFreeObservedNeighbor(lobs, myPosition, lastPosition, occupiedObservedNodes, agent, nextNode);
                if (fallback != null && !fallback.equals(nextNode)) {
                    moved = ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(fallback));
                    System.out.println(agent.getLocalName() + " [EXPLORE] fallback to " + fallback + " success=" + moved);
                    if (moved) {
                        agent.setNextDest(fallback);
                        agent.clearFailedStenchMove(fallback);
                        agent.pruneInvalidInferredGolemsAt(fallback);
                    }
                } else {
                    agent.setNextDest(null);
                }
            }
        }
        agent.cleanPosition();
        agent.cleanStenchDirection();
        agent.cleanInsideStench();
        agent.cleanNextNodes();
    }

    private void updateOwnStench(FSMExploAgent agent, String myPosition, List<String> nodeStench) {
        if (nodeStench.size() == 1) {
            agent.setOwnStenchDirection(nodeStench.get(0));
            agent.setOwnInsideStench(null);
        } else if (nodeStench.size() > 1) {
            agent.setOwnStenchDirection(null);
            agent.setOwnInsideStench(myPosition);
        } else {
            agent.setOwnStenchDirection(null);
            agent.setOwnInsideStench(null);
        }

        if (!nodeStench.isEmpty()) {
            agent.getMyMap().updateScentFromObservation(new java.util.HashSet<>(nodeStench));
        }
    }

    private List<String> getStenchNodes(List<Couple<Location, List<Couple<Observation, String>>>> observations) {
        List<String> nodeStench = new ArrayList<>();
        for (Couple<Location, List<Couple<Observation, String>>> entry : observations) {
            if (hasObservation(entry, Observation.STENCH)) {
                nodeStench.add(entry.getLeft().getLocationId());
            }
        }
        return nodeStench;
    }

    private boolean hasObservation(Couple<Location, List<Couple<Observation, String>>> entry, Observation expected) {
        for (Couple<Observation, String> obs : entry.getRight()) {
            if (obs.getLeft() == expected) {
                return true;
            }
        }
        return false;
    }

    private void handleFailedStenchMove(FSMExploAgent agent, String failedNode, List<String> nodeStench) {
        if (!nodeStench.contains(failedNode)) {
            return;
        }
        if (agent.registerFailedStenchMove(failedNode)) {
            String inferredId = agent.getInferredGolemId(failedNode);
            agent.addOrUpdateGolem(inferredId, failedNode, false);
            agent.setCurrentTargetGolemId(inferredId);
            agent.setMode(FSMExploAgent.MODE_HUNT);
            System.out.println(agent.getLocalName() + " inferred Golem from repeated stench-blocked move at " + failedNode);
        }
    }

    private void updateVisibleGolems(List<Couple<Location, List<Couple<Observation, String>>>> observations,
                                     FSMExploAgent agent) {
        for (Couple<Location, List<Couple<Observation, String>>> entry : observations) {
            String nodeId = entry.getLeft().getLocationId();
            for (Couple<Observation, String> obs : entry.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME && obs.getRight() != null) {
                    String name = obs.getRight();
                    if (agent.isGolemAgentName(name)) {
                        agent.addOrUpdateGolem(name, nodeId, true);
                        agent.setCurrentTargetGolemId(name);
                    }
                }
            }
        }
    }

    private Set<String> getOccupiedObservedNodes(List<Couple<Location, List<Couple<Observation, String>>>> observations) {
        Set<String> occupied = new HashSet<>();
        for (Couple<Location, List<Couple<Observation, String>>> entry : observations) {
            for (Couple<Observation, String> obs : entry.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME && obs.getRight() != null) {
                    occupied.add(entry.getLeft().getLocationId());
                }
            }
        }
        return occupied;
    }

    private boolean isFreeNode(String nodeId, String myPosition, Set<String> occupiedObservedNodes, FSMExploAgent agent) {
        return nodeId != null
                && !nodeId.equals(myPosition)
                && !occupiedObservedNodes.contains(nodeId)
                && !agent.getKnownAgentPositions().contains(nodeId);
    }

    private String chooseFreeObservedNeighbor(List<Couple<Location, List<Couple<Observation, String>>>> observations,
                                              String myPosition,
                                              String lastPosition,
                                              Set<String> occupiedObservedNodes,
                                              FSMExploAgent agent) {
        return chooseFreeObservedNeighbor(observations, myPosition, lastPosition, occupiedObservedNodes, agent, null);
    }

    private String chooseFreeObservedNeighbor(List<Couple<Location, List<Couple<Observation, String>>>> observations,
                                              String myPosition,
                                              String lastPosition,
                                              Set<String> occupiedObservedNodes,
                                              FSMExploAgent agent,
                                              String excludedNode) {
        String fallback = null;
        for (Couple<Location, List<Couple<Observation, String>>> entry : observations) {
            String nodeId = entry.getLeft().getLocationId();
            if (nodeId.equals(excludedNode)) {
                continue;
            }
            if (isFreeNode(nodeId, myPosition, occupiedObservedNodes, agent)) {
                if (!nodeId.equals(lastPosition)) {
                    return nodeId;
                }
                fallback = nodeId;
            }
        }
        return fallback;
    }
}
