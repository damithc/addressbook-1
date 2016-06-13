package address.keybindings;

import address.events.EventManager;
import address.events.GlobalHotkeyEvent;
import address.events.KeyBindingEvent;
import com.google.common.eventbus.Subscribe;
import com.tulskiy.keymaster.common.Provider;

import java.util.List;

/**
 * An adapter that wraps around jKeyMaster hotkey provider.
 * Detects global hotkeys and raises an {@link KeyBindingEvent}
 */
public class GlobalHotkeyProvider {
    /** Provider for global hotkeys */
    private final Provider provider = Provider.getCurrentProvider(false);
    private EventManager eventManager;

    /**
     * Creates an instance and init the global hotkeys.
     * @param eventManager
     */
    GlobalHotkeyProvider(EventManager eventManager){
        this.eventManager = eventManager;
        eventManager.registerHandler(this);
    }

    /**
     * Registers a {@link GlobalHotkeyEvent} with jKeymaster for each global hotkey
     * @param hotkeys
     */
    void registerGlobalHotkeys(List<GlobalHotkey> hotkeys) {
        for (GlobalHotkey hk: hotkeys){
            provider.register(hk.getKeyStroke(),
                    (hotkey) -> eventManager.post(new GlobalHotkeyEvent(hk.keyCombination)));
        }
    }

    /**
     * Swallows the {@link GlobalHotkeyEvent} raised by jKeyMaster and
     * raises the corresponding {@link KeyBindingEvent}
     * @param globalHotkeyEvent
     */
    @Subscribe
    public void handleGlobalHotkeyEvent(GlobalHotkeyEvent globalHotkeyEvent){
        eventManager.post(globalHotkeyEvent.keyBindingEvent);
    }


    /**
     * Resets global hotkeys
     */
    public void clear() {
        provider.reset();
        provider.stop();
    }
}