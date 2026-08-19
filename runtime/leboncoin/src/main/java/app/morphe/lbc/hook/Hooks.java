package app.morphe.lbc.hook;

import app.morphe.lbc.Lbc;
import app.morphe.lbc.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/** Façade utilisée par les plugins : `Hooks.method(...)`, `Hooks.before(...)`. */
public final class Hooks {

    private static final Logger LOG = new Logger("Hooks");

    private static HookBackend backend;

    private Hooks() {
    }

    public static boolean init() {
        backend = new PineBackend();
        return backend.init();
    }

    public static boolean available() {
        return backend != null;
    }

    /** Résout une méthode de l'app par nom de classe (souvent obfusqué, cf. Bindings). */
    public static Method method(String className, String methodName, Class<?>... parameters) {
        try {
            Class<?> owner = Lbc.appClassLoader().loadClass(className);
            Method method = owner.getDeclaredMethod(methodName, parameters);
            method.setAccessible(true);
            return method;
        } catch (Throwable error) {
            LOG.w("méthode introuvable : " + className + "#" + methodName);
            return null;
        }
    }

    public static Constructor<?> constructor(String className, Class<?>... parameters) {
        try {
            Class<?> owner = Lbc.appClassLoader().loadClass(className);
            Constructor<?> ctor = owner.getDeclaredConstructor(parameters);
            ctor.setAccessible(true);
            return ctor;
        } catch (Throwable error) {
            LOG.w("constructeur introuvable : " + className);
            return null;
        }
    }

    public static Object hook(Member target, HookBackend.Callback callback) {
        if (backend == null || target == null) {
            return null;
        }
        return backend.hook(target, callback);
    }

    public static Object before(Member target, Consumer callback) {
        return hook(target, new HookBackend.Callback() {
            @Override
            public void before(HookBackend.Frame frame) {
                callback.accept(frame);
            }
        });
    }

    public static Object after(Member target, Consumer callback) {
        return hook(target, new HookBackend.Callback() {
            @Override
            public void after(HookBackend.Frame frame) {
                callback.accept(frame);
            }
        });
    }

    /** Équivalent minimal de java.util.function.Consumer, compatible API 26. */
    public interface Consumer {
        void accept(HookBackend.Frame frame);
    }
}
