package address.model;

import address.events.*;

import address.exceptions.DuplicateTagException;
import address.main.ComponentManager;
import address.model.datatypes.*;
import address.model.datatypes.person.*;
import address.model.datatypes.tag.Tag;
import address.model.datatypes.UniqueData;
import address.util.AppLogger;
import address.util.LoggerManager;
import address.util.PlatformExecUtil;
import address.util.collections.UnmodifiableObservableList;
import com.google.common.eventbus.Subscribe;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Represents the in-memory model of the address book data.
 * All changes to any model should be synchronized.
 */
public class ModelManager extends ComponentManager implements ReadOnlyAddressBook, ReadOnlyViewableAddressBook {
    private static final AppLogger logger = LoggerManager.getLogger(ModelManager.class);

    private final AddressBook backingModel;
    private final ViewableAddressBook visibleModel;
    private final Map<Integer, ChangePersonInModelCommand> personChangesInProgress;

    private final ScheduledExecutorService scheduler;
    private final Executor commandExecutor;

    private UserPrefs prefs;

    {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        personChangesInProgress = new HashMap<>();
        commandExecutor = Executors.newCachedThreadPool();
    }

    /**
     * Initializes a ModelManager with the given AddressBook
     * AddressBook and its variables should not be null
     */
    public ModelManager(AddressBook src, UserPrefs prefs) {
        super();
        if (src == null) {
            logger.fatal("Attempted to initialize with a null AddressBook");
            assert false;
        }
        logger.debug("Initializing with address book: {}", src);

        backingModel = new AddressBook(src);
        visibleModel = backingModel.createVisibleAddressBook();

        // update changes need to go through #updatePerson or #updateTag to trigger the LMCEvent
        final ListChangeListener<Object> modelChangeListener = change -> {
            while (change.next()) {
                if (change.wasAdded() || change.wasRemoved()) {
                    raise(new LocalModelChangedEvent(this));
                    return;
                }
            }
        };
        backingModel.getPersons().addListener(modelChangeListener);
        backingTagList().addListener(modelChangeListener);

        this.prefs = prefs;
    }

    public ModelManager(UserPrefs prefs) {
        this(new AddressBook(), prefs);
    }

    /**
     * Clears existing backing model and replaces with the provided new data.
     */
    public void resetData(ReadOnlyAddressBook newData) {
        backingModel.resetData(newData);
    }

    public void initData(ReadOnlyAddressBook initialData) {
        resetData(initialData);
    }

    public void clearModel() {
        backingModel.clearData();
    }

//// EXPOSING MODEL

    /**
     * @return all persons in visible model IN AN UNMODIFIABLE VIEW
     */
    @Override
    public UnmodifiableObservableList<ReadOnlyViewablePerson> getAllViewablePersonsReadOnly() {
        return visibleModel.getAllViewablePersonsReadOnly();
    }

    /**
     * @return all tags in backing model IN AN UNMODIFIABLE VIEW
     */
    @Override
    public UnmodifiableObservableList<Tag> getAllViewableTagsReadOnly() {
        return visibleModel.getAllViewableTagsReadOnly();
    }

    @Override
    public List<ReadOnlyPerson> getPersonList() {
        return backingModel.getPersonList();
    }

    @Override
    public List<Tag> getTagList() {
        return backingModel.getTagList();
    }

    @Override
    public UnmodifiableObservableList<ReadOnlyPerson> getPersonsAsReadOnlyObservableList() {
        return backingModel.getPersonsAsReadOnlyObservableList();
    }

    @Override
    public UnmodifiableObservableList<Tag> getTagsAsReadOnlyObservableList() {
        return backingModel.getTagsAsReadOnlyObservableList();
    }

    /**
     * @return reference to the tags list inside backing model
     */
    private ObservableList<Tag> backingTagList() {
        return backingModel.getTags();
    }

    AddressBook backingModel() {
        return backingModel;
    }

    ViewableAddressBook visibleModel() {
        return visibleModel;
    }

//// USER COMMANDS

    /**
     * Request to create a person. Simulates the change optimistically until remote confirmation, and provides a grace
     * period for cancellation, editing, or deleting.
     * @param userInputRetriever a callback to retrieve the user's input. Will be run on fx application thread
     */
    public synchronized void createPersonFromUI(Callable<Optional<ReadOnlyPerson>> userInputRetriever) {
        final int GRACE_PERIOD_DURATION = 3;
        final Supplier<Optional<ReadOnlyPerson>> fxThreadedInputRetriever = () -> {
          try {
              return PlatformExecUtil.callLater(userInputRetriever).get();
          } catch (InterruptedException | ExecutionException e) {
              e.printStackTrace();
              return Optional.empty(); // execution exception, unable to retrieve data
          }
        };
        commandExecutor.execute(new AddPersonCommand(fxThreadedInputRetriever, GRACE_PERIOD_DURATION, eventManager, this));
    }

    public synchronized void cancelPersonChangeCommand(ReadOnlyPerson target) {

    }

    /**
     * @param targetPersonId id of person being changed
     * @param changeInProgress the active change command on the person with id {@code targetPersonId}
     */
    synchronized void assignOngoingChangeToPerson(int targetPersonId, ChangePersonInModelCommand changeInProgress) {
        assert targetPersonId != changeInProgress.getTargetPersonId() : "Must map to correct id";
        if (personChangesInProgress.containsKey(targetPersonId)) {
            throw new IllegalStateException("Only 1 ongoing change allowed per person.");
        }
        personChangesInProgress.put(targetPersonId, changeInProgress);
    }

    /**
     * Removed the target person's mapped changeInProgress, freeing it for other change commands.
     * @return the removed change command, or null if there was no mapping found
     */
    synchronized ChangePersonInModelCommand unassignOngoingChangeForPerson(int targetPersonId) {
        return personChangesInProgress.remove(targetPersonId);
    }

//// CREATE

    synchronized void addViewablePerson(ViewablePerson vp) {
        visibleModel.addPerson(vp);
    }

    // deprecated, to replace by remote assignment
    public int generatePersonId() {
        int id;
        do {
            id = Math.abs(UUID.randomUUID().hashCode());
        } while (id == 0 || backingModel.containsPerson(id));
        return id;
    }

    /**
     * Adds a tag to the model
     * @param tagToAdd
     * @throws DuplicateTagException when this operation would cause duplicates
     */
    public synchronized void addTagToBackingModel(Tag tagToAdd) throws DuplicateTagException {
        if (backingTagList().contains(tagToAdd)) {
            throw new DuplicateTagException(tagToAdd);
        }
        backingTagList().add(tagToAdd);
    }

//// READ


//// UPDATE

    /**
     * Updates the details of a Person object. Updates to Person objects should be
     * done through this method to ensure the proper events are raised to indicate
     * a change to the model. TODO listen on Person properties and not manually raise events here.
     * @param target The Person object to be changed.
     * @param updatedData The temporary Person object containing new values.
     */
    public synchronized void updatePerson(ReadOnlyPerson target, ReadOnlyPerson updatedData) {
        backingModel.findPerson(target).get().update(updatedData);
        raise(new LocalModelChangedEvent(this));
    }

    /**
     * Updates the details of a Tag object. Updates to Tag objects should be
     * done through this method to ensure the proper events are raised to indicate
     * a change to the model. TODO listen on Tag properties and not manually raise events here.
     *
     * @param original The Tag object to be changed.
     * @param updated The temporary Tag object containing new values.
     */
    public synchronized void updateTag(Tag original, Tag updated) throws DuplicateTagException {
        if (!original.equals(updated) && backingTagList().contains(updated)) {
            throw new DuplicateTagException(updated);
        }
        original.update(updated);
        raise(new LocalModelChangedEvent(this));
    }

//// DELETE

    /**
     * Deletes the person from the model.
     * @param personToDelete
     * @return true if there was a successful removal
     */
    public synchronized boolean deletePerson(ReadOnlyPerson personToDelete){
        boolean b = backingModel.removePerson(personToDelete);
        return b;
    }

    public void delayedDeletePerson(ReadOnlyPerson toDelete, int delay, TimeUnit step) {
        final Optional<ViewablePerson> deleteTarget = visibleModel.findPerson(toDelete);
        assert deleteTarget.isPresent();
        deleteTarget.get().setIsDeleted(true);
        scheduler.schedule(()-> Platform.runLater(() ->
                deletePerson(deleteTarget.get())), delay, step);
    }

    /**
     * Deletes the tag from the model.
     * @param tagToDelete
     * @return true if there was a successful removal
     */
    public synchronized boolean deleteTag(Tag tagToDelete) {
        return backingTagList().remove(tagToDelete);
    }

//// EVENT HANDLERS

    @Subscribe
    private <T> void handleUpdateCompletedEvent(UpdateCompletedEvent<T> uce) {
        // Sync is done outside FX Application thread
        // TODO: Decide how incoming updates should be handled
    }

//// DIFFERENTIAL UPDATE ENGINE todo shift this logic to sync component (with conditional requests to remote)

    /**
     * Diffs extData with the current model and updates the current model with minimal change.
     * @param extData data from an external canonical source
     */
    public synchronized void updateUsingExternalData(ReadOnlyAddressBook extData) {
        final AddressBook data = new AddressBook(extData);
        assert !data.containsDuplicates() : "Duplicates are not allowed in an AddressBook";
        boolean hasPersonsUpdates = diffUpdate(backingModel.getPersons(), data.getPersons());
        boolean hasTagsUpdates = diffUpdate(backingTagList(), data.getTags());
        if (hasPersonsUpdates || hasTagsUpdates) {
            raise(new LocalModelChangedEvent(this));
        }
    }

    /**
     * Performs a diff-update (minimal change) on target using newData.
     * Arguments newData and target should contain no duplicates.
     *
     * Does NOT trigger any events.
     *
     * Specification:
     *   _________________________________________________
     *  | in newData | in target | Result                |
     *  --------------------------------------------------
     *  | yes        | yes       | update item in target |
     *  | yes        | no        | remove from target    |
     *  | no         | yes       | copy-add to target    |
     *  | no         | no        | N/A                   |
     *  --------------------------------------------------
     * Any form of data element ordering in newData will not be enforced on target.
     *
     * @param target collection of data items to be updated
     * @param newData target will be updated to match newData's state
     * @return true if there were changes from the update.
     */
    private synchronized <E extends UniqueData> boolean diffUpdate(Collection<E> target, Collection<E> newData) {
        assert UniqueData.itemsAreUnique(target) : "target of diffUpdate should not have duplicates";
        assert UniqueData.itemsAreUnique(newData) : "newData for diffUpdate should not have duplicates";

        final Map<E, E> remaining = new HashMap<>(); // has to be map; sets do not allow elemental retrieval
        newData.forEach((item) -> remaining.put(item, item));

        final Set<E> toBeRemoved = new HashSet<>();
        final AtomicBoolean changed = new AtomicBoolean(false);

        // handle updates to existing data objects
        target.forEach(oldItem -> {
            final E newItem = remaining.remove(oldItem); // find matching item in unconsidered new data
            if (newItem == null) { // not in newData
                toBeRemoved.add(oldItem);
            } else { // exists in both new and old, update.
                updateDataItem(oldItem, newItem); // updates the items in target (reference points back to target)
                changed.set(true);
            }
        });

        final Set<E> toBeAdded = remaining.keySet();

        target.removeAll(toBeRemoved);
        target.addAll(toBeAdded);

        return changed.get() || toBeAdded.size() > 0 || toBeRemoved.size() > 0;
    }

    /**
     * Allows generic UniqueData .update() calling without having to know which class it is.
     * Because java does not allow self-referential generic type parameters.
     *
     * Does not trigger any events.
     *
     * @param target to be updated
     * @param newData data used for update
     */
    private <E extends UniqueData> void updateDataItem(E target, E newData) {
        if (target instanceof Person && newData instanceof Person) {
            ((Person) target).update((Person) newData);
            return;
        }
        if (target instanceof Tag && newData instanceof Tag) {
            ((Tag) target).update((Tag) newData);
            return;
        }
        assert false : "need to add logic for any new UniqueData classes";
    }

    public UserPrefs getPrefs() {
        return prefs;
    }

    public void setPrefsSaveLocation(String saveLocation) {
        prefs.setSaveLocation(saveLocation);
        raise(new SaveLocationChangedEvent(saveLocation));
        raise(new SavePrefsRequestEvent(prefs));
    }

    public void clearPrefsSaveLocation() {
        setPrefsSaveLocation(null);
    }

}
