package address.model;

import static address.model.ChangeObjectInModelCommand.State.*;
import address.events.BaseEvent;
import address.model.datatypes.person.ReadOnlyPerson;
import address.model.datatypes.person.ViewablePerson;
import address.util.PlatformExecUtil;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles optimistic UI updating, cancellation/changing command, and remote consistency logic
 * for deleting a person from the addressbook.
 */
public class DeletePersonCommand extends ChangePersonInModelCommand {

    private final Consumer<? extends BaseEvent> eventRaiser;
    private final ModelManager model;
    private final ViewablePerson target;

    /**
     * @see super#ChangePersonInModelCommand(Supplier, int)
     */
    protected DeletePersonCommand(ViewablePerson target, int gracePeriodDurationInSeconds,
                                  Consumer<? extends BaseEvent> eventRaiser, ModelManager model) {
        // no input needed for delete commands
        super(() -> Optional.of(target), gracePeriodDurationInSeconds);
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
            target.setIsDeleted(false);
        });
        model.unassignOngoingChangeForPerson(target.getId());
    }

    @Override
    protected State simulateResult() {
        PlatformExecUtil.runAndWait(() ->
                target.setIsDeleted(true));
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
        model.execNewEditPersonCommand(target, editInputSupplier);
        return CANCELLED;
    }

    @Override
    protected State handleDeleteInGracePeriod() {
        return GRACE_PERIOD; // nothing to be done
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
        // TODO: update when remote request api is complete
        PlatformExecUtil.runAndWait(() -> model.backingModel().removePerson(target));
        return SUCCESSFUL;
    }

    @Override
    protected void finishWithCancel() {
        // nothing needed
    }

    @Override
    protected void finishWithSuccess() {
        // Nothing to do for now
    }

    @Override
    protected void finishWithFailure() {
        // Impossible for now
    }
}
