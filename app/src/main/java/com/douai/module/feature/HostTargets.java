package com.douai.module.feature;

import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Resolves only scan-proven targets; reflection never substitutes a first candidate. */
public final class HostTargets {
    private static final HostReflectionCache ACCESSORS = new HostReflectionCache();
    private HostTargets() { }

    public static HostCandidate candidate(HostScanReport report, String id) {
        RuleScanResult result = report == null ? null : report.getResults().get(id);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE
                || result.getCandidates().size() != 1) {
            throw new IllegalStateException("Host rule is not unique: " + id);
        }
        return result.getCandidates().get(0);
    }

    public static Class<?> type(HostScanReport report, String id, ClassLoader loader) throws Exception {
        HostCandidate value = candidate(report, id);
        String descriptor = value.getClassDescriptor();
        return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'), false, loader);
    }

    public static Method method(HostScanReport report, String id, ClassLoader loader) throws Exception {
        HostCandidate value = candidate(report, id);
        Method match = ACCESSORS.declaredMethod(type(report, id, loader), value.getMethodName(),
                value.getMethodDescriptor());
        if (match == null) throw new NoSuchMethodException(id);
        return match;
    }

    public static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = ACCESSORS.field(owner, name);
        if (field != null) return field;
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    public static Object invoke(Object owner, String name) {
        if (owner == null) return null;
        try {
            Method method = ACCESSORS.publicNoArgMethod(owner.getClass(), name);
            return method == null ? null : method.invoke(owner);
        }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    public static Object read(Object owner, String name) {
        if (owner == null) return null;
        try {
            Field field = ACCESSORS.field(owner.getClass(), name);
            return field == null ? null : field.get(owner);
        }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    public static String descriptor(Method method) {
        StringBuilder value = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) value.append(descriptor(parameter));
        return value.append(')').append(descriptor(method.getReturnType())).toString();
    }

    public static String descriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException(type.getName());
    }
}
