package fileshare.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What each side needs to remember between rounds.
 *
 * The model is deliberately a plain queue, held by whichever side is sending.
 * Pausing removes a file from that queue; resuming puts it back at the end. A
 * paused file is therefore never offered, which means the receiving side needs
 * no notion of "paused" at all -- it only has to remember what it has already
 * said yes or no to.
 *
 * An earlier version kept paused, auto-accept and declined sets on both sides
 * and tried to keep them agreeing. They could not, and a file that was paused,
 * resumed and paused again would end up marked declined on one side and waiting
 * on the other.
 */
public final class TransferState {

    /** Files the receiver has agreed to. A resumed file is still agreed to. */
    private final Set<String> accepted = Collections.synchronizedSet(new HashSet<String>());

    /** Files the receiver said no to. Never asked about again this session. */
    private final Set<String> declined = Collections.synchronizedSet(new HashSet<String>());

    private volatile String pauseId;
    private volatile String cancelId;

    /** Consulted between chunks by the transfer engine. */
    public final Io.Control control = new Io.Control() {
        @Override public Io.Act check(Item it) {
            if (it.id.equals(cancelId)) { cancelId = null; return Io.Act.CANCEL; }
            if (it.id.equals(pauseId))  { pauseId = null;  return Io.Act.PAUSE;  }
            return Io.Act.GO;
        }
    };

    public void requestPause(String id) { pauseId = id; }

    public void requestCancel(String id) { cancelId = id; }

    public void clearRequests() {
        pauseId = null;
        cancelId = null;
    }

    // ---- receiver bookkeeping -----------------------------------------

    public void noteAccepted(String id) {
        accepted.add(id);
        declined.remove(id);
    }

    public void noteDeclined(String id) {
        declined.add(id);
        accepted.remove(id);
    }

    public void forget(String id) {
        accepted.remove(id);
        declined.remove(id);
    }

    public boolean isDeclined(String id) { return declined.contains(id); }

    public void clear() {
        accepted.clear();
        declined.clear();
        clearRequests();
    }

    /**
     * Splits an offer into what is already settled and what needs the user.
     *
     * Anything previously accepted is taken again without asking: it is the same
     * transfer carrying on. Anything previously declined stays declined. Only
     * genuinely new files reach the prompt.
     */
    public boolean[] preDecide(List<Item> offered, List<Item> ask, List<Integer> askIndex) {
        boolean[] answer = new boolean[offered.size()];
        for (int i = 0; i < offered.size(); i++) {
            Item it = offered.get(i);
            if (declined.contains(it.id)) {
                answer[i] = false;
            } else if (accepted.contains(it.id)) {
                answer[i] = true;
            } else {
                ask.add(it);
                askIndex.add(Integer.valueOf(i));
            }
        }
        return answer;
    }
}
