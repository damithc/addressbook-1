package address.model;

import static address.model.ChangeObjectInModelCommand.State.*;
import address.events.BaseEvent;
import address.events.LocalModelChangedEvent;
import address.model.datatypes.person.ReadOnlyPerson;
import address.model.datatypes.person.ViewablePerson;
import address.util.PlatformExecUtil;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles optimistic UI updating, cancellation/changing command, and remote consistency logic
 * for editing a person in the addressbook.
 */
public class EditPersonCommand extends ChangePersonInModelCommand {

    private final Consumer<? extends BaseEvent> eventRaiser;
    private final ModelManager model;
    private final ViewablePerson target;

    /**
     * @param inputRetriever               Will run on execution {@link #run()} thread. This should handle thread concurrency
     *                                     logic (eg. {@link PlatformExecUtil#call(Callable)} within itself.
     *                                     If the returned Optional is empty, the command will be cancelled.
     * @see super#ChangePersonInModelCommand(Supplier, int)
     */
    protected EditPersonCommand(ViewablePerson target, Supplier<Optional<ReadOnlyPerson>> inputRetriever,
                                int gracePeriodDurationInSeconds, Consumer<? extends BaseEvent> eventRaiser,
                                ModelManager model) {
        super(inputRetriever, gracePeriodDurationInSeconds);
        this.target = target;
        this.model = model;
        this.eventRaiser = eventRaiser;
    }

    @Override
    public int getTargetPersonId() {
        return target.getId();
    }

    @Override
    protected void before() {
        if (model.personHasOngoingChange(target)) {
            try {
                model.getOngoingChangeForPerson(target).waitForCompletion();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        model.assignOngoingChangeToPerson(target.getId(), this);
        target.stopSyncingWithBackingObject();
    }

    @Override
    protected void after() {
        PlatformExecUtil.runAndWait(() -> {
            target.continueSyncingWithBackingObject();
            target.forceSyncFromBacking();
            target.setIsEdited(false);
        });
        model.unassignOngoingChangeForPerson(target.getId());
    }

    @Override
    protected State simulateResult() {
        assert input != null;
        PlatformExecUtil.runAndWait(() -> {
            target.setIsEdited(true);
            target.simulateUpdate(input);
        });
        return GRACE_PERIOD;
    }

    @Override
    protected void beforeGracePeriod() {
        // nothing needed for now
    }

    @Override
    protected void handleChangeToSecondsLeftInGracePeriod(int secondsLeft) {
        PlatformExecUtil.runLater(() -> target.setSecondsLeftInPendingState(secondsLeft));
    }

    @Override
    protected State handleEditInGracePeriod(Supplier<Optional<ReadOnlyPerson>> editInputSupplier) {
        // take details and update viewable, then restart grace period
        final Optional<ReadOnlyPerson> editInput = editInputSupplier.get();
        if (editInput.isPresent()) { // edit request confirmed
            input = editInput.get(); // update saved input
            PlatformExecUtil.runAndWait(() -> target.simulateUpdate(input));
        }
        return GRACE_PERIOD; // restart grace period
    }

    @Override
    protected State handleDeleteInGracePeriod() {
        model.execNewDeletePersonCommand(target);
        return CANCELLED;
    }

    @Override
    protected State handleCancelInGracePeriod() {
        return CANCELLED;
    }

    @Override
    protected Optional<ReadOnlyPerson> getRemoteConflict() {
        return Optional.empty(); // TODO add after cloud individual check implemented
    }

    @Override
    protected State resolveRemoteConflict(ReadOnlyPerson remoteVersion) {
        assert false; // TODO figure out what to show to users
        return null;
    }

    @Override
    protected State requestChangeToRemote() {
        assert input != null;
        // TODO: update when remote request api is complete
        PlatformExecUtil.runAndWait(() -> target.getBacking().update(input));
        return SUCCESSFUL;
    }

    @Override
    protected void finishWithCancel() {
        // for now, already handled by #after()
    }

    @Override
    protected void finishWithSuccess() {
        model.raiseLocalModelChangedEvent();
    }

    @Override
    protected void finishWithFailure() {
        // can't happen yet
    }
}
