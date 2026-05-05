package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.FSMExploAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.GolemInfo;
import jade.core.behaviours.OneShotBehaviour;

public class BlockerBehaviour extends OneShotBehaviour {

    private static final long serialVersionUID = 1L;
    private FSMExploAgent agent;
    private String activeTargetNode;
    private long startTime;
    private static final long MAX_BLOCK_TIME = 60000; // 60 seconds timeout

    public BlockerBehaviour(FSMExploAgent agent) {
        super(agent);
        this.agent = agent;
    }

    @Override
    public void action() {
        try { Thread.sleep(800); } catch (InterruptedException e) {}

        String targetNode = agent.getBlockingNode();
        Set<String> targetGolems = getActiveBlockingTargets();

        if (targetNode == null || targetGolems.isEmpty()) {
            System.out.println(agent.getLocalName() + " [BLOCKING] no active target, returning to hunt.");
            leaveBlockingMode();
            return;
        }

        if (!targetNode.equals(activeTargetNode)) {
            activeTargetNode = targetNode;
            startTime = System.currentTimeMillis();
        }

        String myPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
        agent.pruneInvalidInferredGolemsAt(myPos);

        // If not yet at target node, move towards it
        if (!myPos.equals(targetNode)) {
            if (agent.getKnownAgentPositions().contains(targetNode)) {
                System.out.println(agent.getLocalName() + " [BLOCKING] target " + targetNode
                        + " already occupied, waiting.");
                agent.setMode(FSMExploAgent.MODE_BLOCKING);
                return;
            }

            List<String> path = agent.getMyMap().getShortestPath(myPos, targetNode);
            if (path != null && !path.isEmpty()) {
                String nextStep = path.get(0);
                if (agent.getKnownAgentPositions().contains(nextStep)) {
                    System.out.println(agent.getLocalName() + " [BLOCKING] next step " + nextStep
                            + " occupied, waiting.");
                    agent.setMode(FSMExploAgent.MODE_BLOCKING);
                    return;
                }
                boolean moved = ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(nextStep));
                System.out.println(agent.getLocalName() + " [BLOCKING] moving to " + nextStep + " success=" + moved);
                if (moved) {
                    agent.pruneInvalidInferredGolemsAt(nextStep);
                }
            } else {
                System.out.println(agent.getLocalName() + " [BLOCKING] unreachable target " + targetNode + ", aborting block.");
                leaveBlockingMode();
            }
            return;
        }

        // At target node, check if all target Golems are captured
        boolean allCaptured = true;
        for (String gid : targetGolems) {
            if (!agent.getCapturedGolems().contains(gid)) {
                allCaptured = false;
                break;
            }
        }

        if (allCaptured) {
            System.out.println(agent.getLocalName() + " [BLOCKING] all targets captured, leaving.");
            leaveBlockingMode();
            return;
        }

        for (String gid : targetGolems) {
            GolemInfo golem = agent.getKnownGolems().get(gid);
            if (golem != null && !golem.isConfirmed()) {
                continue;
            }
            if (golem != null && isGolemSurrounded(golem.getLastKnownPosition(), agent)) {
                System.out.println(agent.getLocalName() + " [BLOCKING] captured surrounded Golem " + gid);
                agent.markGolemCaptured(gid);
            }
        }

        if (getActiveBlockingTargets().isEmpty()) {
            leaveBlockingMode();
            return;
        }

        // Timeout protection
        if (System.currentTimeMillis() - startTime > MAX_BLOCK_TIME) {
            System.out.println(agent.getLocalName() + " [BLOCKING] timeout, aborting block.");
            leaveBlockingMode();
            return;
        }

        // Periodically check if Golem is still there or if we should reposition
        // (simple: just stay put)
        agent.setMode(FSMExploAgent.MODE_BLOCKING);
    }

    private Set<String> getActiveBlockingTargets() {
        Set<String> active = new HashSet<>();
        List<String> stale = new ArrayList<>();
        for (String gid : agent.getBlockingTargets()) {
            if (agent.getCapturedGolems().contains(gid) || agent.getKnownGolems().containsKey(gid)) {
                active.add(gid);
            } else {
                stale.add(gid);
            }
        }
        for (String gid : stale) {
            agent.getBlockingTargets().remove(gid);
        }
        return active;
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

    private void leaveBlockingMode() {
        agent.clearBlockingTargets();
        agent.setBlockingNode(null);
        agent.setMode(FSMExploAgent.MODE_HUNT);
        activeTargetNode = null;
    }
}
