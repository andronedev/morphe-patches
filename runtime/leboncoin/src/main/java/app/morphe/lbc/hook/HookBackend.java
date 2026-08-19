package app.morphe.lbc.hook;

import java.lang.reflect.Member;

/**
 * Abstraction du moteur de hook.
 *
 * <p>Une seule implémentation aujourd'hui ({@link PineBackend}, hooking ART en process sans root),
 * mais l'interface existe pour pouvoir basculer sur LSPosed/LSPatch ou YukiHook si Pine casse
 * sur une version d'Android — c'est historiquement le point de fragilité de ce genre de mod.
 */
public interface HookBackend {

    /** @return false si le backend n'a pas pu s'initialiser sur cet appareil. */
    boolean init();

    /**
     * @param target méthode ou constructeur à intercepter
     * @return un handle à passer à {@link #unhook(Object)}, ou {@code null} en cas d'échec
     */
    Object hook(Member target, Callback callback);

    void unhook(Object handle);

    /** Une interception. Surcharger {@link #before} et/ou {@link #after}. */
    abstract class Callback {

        public void before(Frame frame) {
        }

        public void after(Frame frame) {
        }
    }

    /** Vue portable sur l'appel intercepté. */
    interface Frame {

        Object thisObject();

        Object[] args();

        Object result();

        void setResult(Object value);

        /** Court-circuite la méthode d'origine et renvoie {@code value}. À n'utiliser que dans {@code before}. */
        void skipWith(Object value);
    }
}
