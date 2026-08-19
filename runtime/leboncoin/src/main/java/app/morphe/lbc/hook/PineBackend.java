package app.morphe.lbc.hook;

import app.morphe.lbc.Lbc;
import app.morphe.lbc.Logger;

import java.lang.reflect.Member;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

/**
 * Backend Pine — hooking ART en process, sans root (c'est ce qu'utilise Aliucord).
 *
 * <p>C'est le seul fichier du runtime qui dépend de Pine : si l'API de Pine change, ou si on
 * bascule sur un autre moteur, il n'y a que cette classe à réécrire.
 */
public final class PineBackend implements HookBackend {

    private static final Logger LOG = new Logger("Pine");

    private boolean ready;

    @Override
    public boolean init() {
        if (ready) {
            return true;
        }
        try {
            PineConfig.debug = Lbc.isDebug();
            PineConfig.debuggable = false;
            // Nécessaire pour atteindre les API masquées d'Android depuis un contexte non système.
            Pine.disableHiddenApiPolicy(true, true);
            ready = true;
        } catch (Throwable error) {
            LOG.e("Pine indisponible sur cet appareil", error);
            ready = false;
        }
        return ready;
    }

    @Override
    public Object hook(Member target, Callback callback) {
        if (!ready || target == null) {
            return null;
        }
        try {
            return Pine.hook(target, new MethodHook() {

                @Override
                public void beforeCall(Pine.CallFrame frame) {
                    try {
                        callback.before(new PineFrame(frame));
                    } catch (Throwable error) {
                        LOG.e("before() a levé une exception sur " + target, error);
                    }
                }

                @Override
                public void afterCall(Pine.CallFrame frame) {
                    try {
                        callback.after(new PineFrame(frame));
                    } catch (Throwable error) {
                        LOG.e("after() a levé une exception sur " + target, error);
                    }
                }
            });
        } catch (Throwable error) {
            LOG.e("hook impossible sur " + target, error);
            return null;
        }
    }

    @Override
    public void unhook(Object handle) {
        // Pine ne fournit pas de retrait fiable de hook : on neutralise côté callback
        // (drapeau `enabled` du plugin) plutôt que de détacher le hook.
    }

    private static final class PineFrame implements Frame {

        private final Pine.CallFrame frame;

        PineFrame(Pine.CallFrame frame) {
            this.frame = frame;
        }

        @Override
        public Object thisObject() {
            return frame.thisObject;
        }

        @Override
        public Object[] args() {
            return frame.args;
        }

        @Override
        public Object result() {
            return frame.getResult();
        }

        @Override
        public void setResult(Object value) {
            frame.setResult(value);
        }

        @Override
        public void skipWith(Object value) {
            // Dans Pine, fixer le résultat depuis beforeCall court-circuite la méthode d'origine.
            frame.setResult(value);
        }
    }
}
