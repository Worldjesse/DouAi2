package com.douai.module.scan;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.MethodReferenceRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Independently audited original-APK names, not scanner rules or a behavioral emulator.
 * 40.6 names are normalized to 40.8 names only to share semantic assertions. Type aliases
 * and full owner/signature method aliases are reversible and injective. Opcodes, arguments,
 * registers, field names, string/literal values, branches and return instructions are untouched.
 * Real structural differences, such as the factory invoke bridge and safety discriminators,
 * are checked separately by each suite's actual Manifest-selected profile.
 */
final class HostContractNameFacts {
    private final boolean legacy;
    private final Map<String, String> types = new HashMap<>();
    private final Map<String, String> methods = new HashMap<>();
    private final Set<String> typeTargets = new HashSet<>();
    private final Set<String> methodTargets = new HashSet<>();
    private final DexRewriter rewriter;

    HostContractNameFacts(boolean legacy) {
        this.legacy = legacy;
        for (String[] pair : new String[][]{
                {"LX/0dFd;", "LX/14LK;"},
                {"LX/0dHa;", "LX/14Jz;"},
                {"LX/0dKM;", "LX/14Ec;"},
                {"LX/0dKN;", "LX/14Ea;"},
                {"LX/0dPP;", "LX/14PD;"},
                {"LX/0dPQ;", "LX/14PE;"},
                {"LX/0dU1;", "LX/14jX;"},
                {"LX/0y6L;", "LX/13a7;"},
                {"LX/0y6l;", "LX/13ao;"},
                {"LX/0y6o;", "LX/13ar;"},
                {"LX/0yAP;", "LX/13eY;"},
                {"LX/0yAS;", "LX/13ea;"},
                {"LX/0yFs;", "LX/13hi;"},
                {"LX/0yTH;", "LX/13wP;"},
                {"LX/1Qjr;", "LX/1Z8C;"},
                {"LX/00Wd;", "LX/00s8;"},
                {"LX/0RS2;", "LX/0V8X;"},
                {"LX/0d9z;", "LX/14Gs;"},
                {"LX/0dCx;", "LX/14J4;"},
                {"LX/0dED;", "LX/14Mk;"},
                {"LX/0dEE;", "LX/14Ml;"},
                {"LX/0dHB;", "LX/14MH;"},
                {"LX/0dH3;", "LX/14MA;"},
                {"LX/0dH8;", "LX/14Kj;"},
                {"LX/0dHH;", "LX/14MD;"},
                {"LX/0dHN;", "LX/14HZ;"},
                {"LX/0dI8;", "LX/14Ep;"},
                {"LX/0dLF;", "LX/14Es;"},
                {"LX/0dMx;", "LX/159L;"},
                {"LX/0dN2;", "LX/14F9;"},
                {"LX/0dN5;", "LX/14FE;"},
                {"LX/0eOw;", "LX/14u0;"},
                {"LY/AObjectS331S0000000_23;", "LY/AObjectS353S0000000_36;"},
                {"LX/0rwU;", "LX/0vbu;"},
                {"LX/0y6z;", "LX/156M;"},
                {"LX/0xyq;", "LX/155h;"},
                {"LX/0yH8;", "LX/13hD;"},
                {"LX/0yFq;", "LX/13hg;"},
                {"LX/0yH0;", "LX/13iG;"},
                {"LX/0yGX;", "LX/13gy;"},
                {"LX/0yXF;", "LX/12j8;"},
                {"LX/15MJ;", "LX/1SVO;"},
                {"LX/1QkP;", "LX/1Z99;"},
                {"LX/1QjB;", "LX/1Z9A;"},
                {"LX/1QW8;", "LX/16Pt;"},
                {"LX/1Qd0;", "LX/1Z5F;"},
                {"LX/1Qd1;", "LX/1Z6z;"},
                {"LX/1QdT;", "LX/1Z5A;"},
                {"LX/1Qh6;", "LX/1Z6p;"},
                {"LX/1QuJ;", "LX/16Tc;"},
                {"LY/ATCallbackS222S1200000_49;", "LY/ATCallbackS220S1200000_37;"},
                {"LY/ATRunnableS33S1110000_49;", "LY/ATRunnableS26S1110000_37;"},
                {"LX/0s5x;", "LX/0Yca;"},
                {"LX/0s61;", "LX/0Yce;"},
                {"LX/0s65;", "LX/0YRa;"},
                {"LX/0s5u;", "LX/0YcX;"},
                {"LX/1Mas;", "LX/1WHh;"},
                {"LX/1Br3;", "LX/0isP;"},
                {"LX/1Bq2;", "LX/0ioG;"},
                {"LX/1Bpx;", "LX/0io8;"},
        }) {
            if (types.put(pair[0], pair[1]) != null || !typeTargets.add(pair[1]))
                throw new AssertionError("Non-injective original type alias");
        }
        String comment = "Lcom/ss/android/ugc/aweme/comment/model/Comment;";
        method(comment, "LJLLLLLL()" + comment, "LJZ");
        method(comment, "LLIIIILZ()Ljava/lang/String;", "LLIIIJ");
        method(comment, "LLLLIILLL()Ljava/lang/String;", "LLLLIILL");
        method(comment, "LLLLL()Ljava/lang/String;", "LLLLJI");
        method("Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;", "LLIIIILZ()I", "LLIIIJ");
        method("LX/0RS2;", "LLLLJ(Ljava/util/HashMap;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "LLLLJI");
        method("LX/1QdT;", "L2(LX/1Qd0;)V", "M2");
        method("LX/1Qh6;", "N3(Ljava/lang/String;Lcom/bytedance/im/core/model/Conversation;Ljava/util/List;Ljava/lang/Integer;LX/1Qd1;Ljava/util/ArrayList;Z)V", "R3");
        String conversations = "Lcom/bytedance/im/core/model/ConversationListModel;";
        method(conversations, "LJJZZI(Ljava/lang/String;ZLcom/bytedance/im/core/client/callback/IRequestListener;)V", "LJLIIL");
        method(conversations, "b3(Ljava/lang/String;[Lcom/bytedance/im/core/model/Conversation;)V", "f3");
        String logic = "Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/new/InputDrawerLogic2;";
        method(logic, "LJJZ()Landroidx/lifecycle/MutableLiveData;", "LJLI");
        method(logic, "LJJZZIII()Landroidx/lifecycle/MutableLiveData;", "LJLIIL");
        method("Lcom/ss/android/ugc/aweme/homepage/tab/bottom/MPFBottomTabComponent;",
                "LJJL(Landroid/view/ViewGroup;)Landroid/view/View;", "LJJLIIIJL");
        rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                return HostContractNameFacts.this::renamedType;
            }
            @Override public Rewriter<MethodReference> getMethodReferenceRewriter(Rewriters rewriters) {
                return new MethodReferenceRewriter(rewriters) {
                    @Override public MethodReference rewrite(MethodReference original) {
                        MethodReference renamedTypes = super.rewrite(original);
                        String name = methods.get(signature(original));
                        if (name == null) {
                            name = original.getName();
                            if (methodTargets.contains(signature(renamedTypes))) name = "__douai_original406_" + name;
                        }
                        return new ImmutableMethodReference(renamedTypes.getDefiningClass(), name,
                                renamedTypes.getParameterTypes(), renamedTypes.getReturnType());
                    }
                };
            }
        });
    }

    String type(String original) { return legacy ? renamedType(original) : original; }
    private String renamedType(String original) {
        if (original.startsWith("[")) return "[" + renamedType(original.substring(1));
        String renamed = types.get(original);
        if (renamed != null) return renamed;
        // A 40.6 APK may also contain an unrelated class already using a 40.8 alias.
        // Keep that original class in a reversible separate namespace, never merge it.
        return typeTargets.contains(original) ? "Ldouai/test/original406/" + original.substring(1) : original;
    }
    ClassDef originalFacts(ClassDef original) {
        ClassDef result = ImmutableClassDef.of(legacy ? rewriter.getClassDefRewriter().rewrite(original) : original);
        Set<String> signatures = new HashSet<>();
        int before = 0, after = 0;
        for (var method : original.getMethods()) before++;
        for (var method : result.getMethods()) {
            after++;
            if (!signatures.add(signature(method))) throw new AssertionError("Original method names were merged");
        }
        if (before != after) throw new AssertionError("Original methods were lost during name normalization");
        return result;
    }
    private void method(String owner, String signature, String renamed) {
        String key = owner + "->" + signature;
        String target = types.getOrDefault(owner, owner) + "->" + renamed + signature.substring(signature.indexOf('('));
        for (Map.Entry<String, String> type : types.entrySet()) target = target.replace(type.getKey(), type.getValue());
        if (methods.put(key, renamed) != null || !methodTargets.add(target))
            throw new AssertionError("Non-injective original method alias");
    }
    private static String signature(MethodReference reference) {
        StringBuilder result = new StringBuilder(reference.getDefiningClass()).append("->")
                .append(reference.getName()).append('(');
        for (CharSequence parameter : reference.getParameterTypes()) result.append(parameter);
        return result.append(')').append(reference.getReturnType()).toString();
    }
}
