package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.GolemInfo;
import jade.core.AID;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import java.io.IOException;

public class HuntBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;
    private static final String PROTOCOL_CAPTURE = "CAPTURE";

    private int surroundedCount = 0;
    private String lastCheckedGolemId = null;
    private static final int REQUIRED_SURROUNDED_COUNT = 3;

    public HuntBehaviour(final AbstractDedaleAgent myAgent) {
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

        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();
        Set<String> occupiedObservedNodes = getOccupiedObservedNodes(lobs);
        Set<String> knownAgentPositions = agent.getKnownAgentPositions();

        List<String> nodeStench = new ArrayList<>();
        Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
        while (iter.hasNext()) {
            Couple<Location, List<Couple<Observation, String>>> entry = iter.next();
            String nodeId = entry.getLeft().getLocationId();
            agent.addNextNodes(nodeId);
            if (hasObservation(entry, Observation.STENCH)) {
                nodeStench.add(nodeId);
            }
        }

        // Update own stench observations
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

        // Update scent map for sharing via delta
        if (!nodeStench.isEmpty()) {
            agent.getMyMap().updateScentFromObservation(new java.util.HashSet<>(nodeStench));
        }

        // Detect Golems in sight
        Map<String, String> spottedGolems = detectAllGolems(lobs, agent);
        for (Map.Entry<String, String> e : spottedGolems.entrySet()) {
            // 直接目击：confirmed = true
            agent.addOrUpdateGolem(e.getValue(), e.getKey(), true);
        }

        // Surrounded detection (with consecutive confirmation)
        for (GolemInfo golem : agent.getKnownGolems().values()) {
            if (agent.getCapturedGolems().contains(golem.getId())) continue;
            if (!golem.isConfirmed()) continue;
            String pos = golem.getLastKnownPosition();
            if (myPosition.equals(pos) || agent.getMyMap().getNeighbors(myPosition).contains(pos)) {
                if (isGolemSurrounded(pos, agent)) {
                    if (lastCheckedGolemId == null || !lastCheckedGolemId.equals(golem.getId())) {
                        surroundedCount = 0;
                        lastCheckedGolemId = golem.getId();
                    }
                    surroundedCount++;
                    if (surroundedCount >= REQUIRED_SURROUNDED_COUNT) {
                        captureGolem(golem.getId(), agent);
                        surroundedCount = 0;
                        lastCheckedGolemId = null;
                        return;
                    }
                } else {
                    surroundedCount = 0;
                    lastCheckedGolemId = null;
                }
            }
        }

        // Graph-style hunt: use articulation points to block
        if (agent.getStyle() == 1) {
            for (GolemInfo golem : agent.getKnownGolems().values()) {
                if (agent.getCapturedGolems().contains(golem.getId())) continue;
                Set<String> articulationPoints = agent.getMyMap().getArticulationPoints();
                String golemPos = golem.getLastKnownPosition();
                for (String ap : articulationPoints) {
                    if (ap.equals(myPosition)) {
                        agent.setBlockingNode(ap);
                        agent.addBlockingTarget(golem.getId());
                        agent.setMode(FSMExploAgent.MODE_BLOCKING);
                        System.out.println(agent.getLocalName() + " switching to BLOCKING mode on AP: " + ap);
                        return;
                    } else if (agent.getMyMap().getShortestPath(golemPos, ap) != null &&
                               agent.getMyMap().getShortestPath(golemPos, ap).size() <= 3) {
                        List<String> path = agent.getMyMap().getShortestPath(myPosition, ap);
                        if (path != null && !path.isEmpty()) {
                            nextNode = path.get(0);
                            agent.setBlockingNode(ap);
                            agent.addBlockingTarget(golem.getId());
                        }
                    }
                }
            }
        }

        // If adjacent to a Golem and not already a manager, start CFP
        for (GolemInfo golem : agent.getKnownGolems().values()) {
            if (agent.getCapturedGolems().contains(golem.getId())) {
                continue;
            }
            if (!golem.isConfirmed()) {
                continue;
            }
            String golemPos = golem.getLastKnownPosition();
            List<String> neighbors = agent.getMyMap().getNeighbors(myPosition);
            if (myPosition.equals(golemPos) || neighbors.contains(golemPos)) {
                if (!agent.isManager && agent.activeCFPGolemId == null) {
                    System.out.println(agent.getLocalName() + " [HUNT] adjacent to Golem " + golem.getId() + ", starting CFP");
                    agent.isManager = true;
                    agent.activeCFPGolemId = golem.getId();
                    agent.cfpStartTime = System.currentTimeMillis();
                    agent.addBehaviour(new BlockingCFPBehaviour(agent, golem.getId(), golemPos));
                    return;
                }
            }
        }

        // Target selection
        GolemInfo currentTarget = agent.getKnownGolems().get(agent.getCurrentTargetGolemId());
        if (currentTarget == null
                || agent.getCapturedGolems().contains(currentTarget.getId())
                || !currentTarget.isConfirmed()) {
            GolemInfo best = agent.selectBestTarget(myPosition);
            if (best != null) {
                agent.setCurrentTargetGolemId(best.getId());
                currentTarget = best;
            } else {
                agent.setCurrentTargetGolemId(null);
                currentTarget = null;
            }
        }

        if (currentTarget == null) {
            if (agent.getMyMap().hasOpenNode()) {
                agent.setMode(FSMExploAgent.MODE_EXPLORATION);
            }
            return;
        }

        // Destination management
        if (myPosition.equals(agent.getDestination())) {
            agent.setDestination(null);
            agent.setDestinationAlea(false);
            agent.setDestinationStench(false);
            agent.setDestinationInsideStench(false);
            agent.setDestinationInterblocage(false);
        }

        List<String> targetPath = agent.getMyMap().getShortestPath(myPosition, currentTarget.getLastKnownPosition());
        if (targetPath != null && !targetPath.isEmpty()) {
            nextNode = targetPath.get(0);
        }

        // Movement decision
        if (nextNode == null && lastPosition.equals(myPosition) && knownAgentPositions.contains(agent.getNextDest())) {
            if (!nodeStench.isEmpty() && agent.getStyle() == 1) {
                for (String nb : nodeStench) {
                    if (isFreeNode(nb, myPosition, occupiedObservedNodes, agent)) {
                        nextNode = nb;
                        break;
                    }
                }
            }
            if (nextNode == null) {
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
            }
        } else if (nextNode == null && agent.isDest_wumpusfound() && agent.getDestination() != null) {
            List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
            if (path != null && !path.isEmpty()) nextNode = path.get(0);
        } else if (nextNode == null && lastPosition.equals(myPosition) && !knownAgentPositions.contains(agent.getNextDest()) &&
                !myPosition.equals(agent.getNextDest())) {
            agent.increaseWumpusCnt();
            nextNode = agent.getNextDest();
        } else {
            agent.setWumpusCnt(0);

            if (nodeStench.size() == 1 && agent.getStyle() == 1) {
                if (!nodeStench.get(0).equals(myPosition)) {
                    nextNode = nodeStench.get(0);
                }
            }
            if (nextNode == null && nodeStench.size() > 1 && agent.getStyle() == 1) {
                Collections.shuffle(nodeStench);
                for (String nb : nodeStench) {
                    if (isFreeNode(nb, myPosition, occupiedObservedNodes, agent)) {
                        nextNode = nb;
                        break;
                    }
                }
            }
            if (nextNode == null && agent.getStyle() == 1 && !nodeStench.isEmpty()) {
                String strongestScentNode = agent.getMyMap().getStrongestScentNode();
                if (strongestScentNode != null && !strongestScentNode.equals(myPosition)) {
                    List<String> path = agent.getMyMap().getShortestPath(myPosition, strongestScentNode);
                    if (path != null && !path.isEmpty()) nextNode = path.get(0);
                }
            }
            if (nextNode == null && agent.getDestination() != null && !agent.getDestinationAlea()) {
                List<String> path = agent.getMyMap().getShortestPath(myPosition, agent.getDestination());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }
            if (nextNode == null && !agent.getStenchDirection().isEmpty() && agent.getStyle() == 0) {
                for (String dest : agent.getStenchDirection()) {
                    if (!dest.equals(myPosition)) {
                        agent.setDestination(dest);
                        agent.setDestinationStench(true);
                        agent.setDestinationInsideStench(false);
                        agent.setDestinationAlea(false);
                        List<String> path = agent.getMyMap().getShortestPath(myPosition, dest);
                        if (path != null && !path.isEmpty()) {
                            nextNode = path.get(0);
                            break;
                        }
                    }
                }
            }
            if (nextNode == null && !agent.getInsideStench().isEmpty() && agent.getStyle() == 0) {
                List<String> inside = new ArrayList<>(agent.getInsideStench());
                Collections.shuffle(inside);
                for (String dest : inside) {
                    if (!dest.equals(myPosition)) {
                        agent.setDestination(dest);
                        agent.setDestinationInsideStench(true);
                        agent.setDestinationStench(false);
                        agent.setDestinationAlea(false);
                        List<String> path = agent.getMyMap().getShortestPath(myPosition, dest);
                        if (path != null && !path.isEmpty()) {
                            nextNode = path.get(0);
                            break;
                        }
                    }
                }
            }
            if (nextNode == null) {
                String rando = null;
                int attempts = 0;
                while ((rando == null || rando.equals(myPosition)) && attempts < 50) {
                    rando = (agent.getStyle() == 0)
                            ? agent.getMyMap().getRandomOneNode()
                            : agent.getMyMap().getRandomNode();
                    attempts++;
                }
                if (rando != null && !rando.equals(myPosition)) {
                    agent.setDestination(rando);
                    agent.setDestinationAlea(true);
                    agent.setDestinationStench(false);
                    agent.setDestinationInsideStench(false);
                    List<String> path = agent.getMyMap().getShortestPath(myPosition, rando);
                    if (path != null && !path.isEmpty()) nextNode = path.get(0);
                }
            }
        }

        // Fallback: move towards current target Golem
        if (nextNode == null && agent.getCurrentTargetGolemId() != null) {
            GolemInfo target = agent.getKnownGolems().get(agent.getCurrentTargetGolemId());
            if (target != null) {
                List<String> path = agent.getMyMap().getShortestPath(myPosition, target.getLastKnownPosition());
                if (path != null && !path.isEmpty()) nextNode = path.get(0);
            }
        }

        if (!isFreeNode(nextNode, myPosition, occupiedObservedNodes, agent)) {
            String fallback = chooseFreeObservedNeighbor(lobs, myPosition, lastPosition, occupiedObservedNodes, agent);
            if (fallback != null) {
                nextNode = fallback;
            }
        }

        agent.setLastPosition(myPosition);
        agent.setNextDest(nextNode);
        if (nextNode != null) {
            boolean moved = ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNode));
            System.out.println(agent.getLocalName() + " [HUNT] moving to " + nextNode + " success=" + moved);
            if (moved) {
                agent.clearFailedStenchMove(nextNode);
                agent.pruneInvalidInferredGolemsAt(nextNode);
            } else {
                handleFailedStenchMove(agent, nextNode, nodeStench);
                String fallback = chooseFreeObservedNeighbor(lobs, myPosition, lastPosition, occupiedObservedNodes, agent, nextNode);
                if (fallback != null && !fallback.equals(nextNode)) {
                    moved = ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(fallback));
                    System.out.println(agent.getLocalName() + " [HUNT] fallback to " + fallback + " success=" + moved);
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
            System.out.println(agent.getLocalName() + " inferred Golem from repeated stench-blocked move at " + failedNode);
        }
    }

    private Map<String, String> detectAllGolems(List<Couple<Location, List<Couple<Observation, String>>>> obs, FSMExploAgent agent) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < obs.size(); i++) {
            String nodeId = obs.get(i).getLeft().getLocationId();
            for (Couple<Observation, String> p : obs.get(i).getRight()) {
                if (p.getLeft() == Observation.AGENTNAME) {
                    System.out.println(agent.getLocalName() + " [DEBUG] sees agent: '" + p.getRight() + "' at " + nodeId);
                }
            }
            Optional<String> golemName = obs.get(i).getRight().stream()
                    .filter(p -> p.getLeft() == Observation.AGENTNAME &&
                            p.getRight() != null &&
                            agent.isGolemAgentName(p.getRight()))
                    .map(Couple::getRight)
                    .findFirst();
            if (golemName.isPresent()) {
                String id = golemName.get();
                result.put(nodeId, id);
                System.out.println(agent.getLocalName() + " [HUNT] Directly spotted Golem at " + nodeId + " -> " + id);
            }
        }
        return result;
    }

    private boolean isGolemSurrounded(String golemNode, FSMExploAgent agent) {
        List<String> neighbours = agent.getMyMap().getNeighbors(golemNode);
        if (neighbours.isEmpty()) return true;
        Set<String> occupied = agent.getKnownAgentPositions();
        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
        occupied.add(myPos);
        occupied.remove(golemNode);

        for (String nb : neighbours) {
            if (!occupied.contains(nb)) {
                return false;
            }
        }
        return true;
    }

    private void captureGolem(String golemId, FSMExploAgent agent) {
        System.out.println(agent.getLocalName() + " [HUNT] *** GOLEM CAPTURED: " + golemId + " ***");
        agent.markGolemCaptured(golemId);
        broadcastCapture(golemId, agent);
    }

    private void broadcastCapture(String golemId, FSMExploAgent agent) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_CAPTURE);
        msg.setSender(myAgent.getAID());
        for (AID aid : agent.getServices("Explorer")) {
            if (!aid.equals(myAgent.getAID())) {
                msg.addReceiver(aid);
            }
        }
        try {
            msg.setContentObject(golemId);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
            System.out.println(agent.getLocalName() + " [SEND] CAPTURE: " + golemId);
        } catch (IOException e) {}
        // Simple retry
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        ((AbstractDedaleAgent) myAgent).sendMessage(msg);
    }
}
