package com.tickloom.checkers;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.PersistentArrayMap;
import clojure.lang.Keyword;

import java.util.HashMap;
import java.util.Map;

/**
 * Main entry point for consistency checking using the new checker infrastructure.
 * Supports both built-in models (register, kv) and custom model objects.
 */
public class ConsistencyChecker {
    private static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    private static final IFn CHECK_LINEARIZABLE;
    private static final IFn CHECK_SEQUENTIAL;
    
    static {
        // Load the new checker namespaces
        REQUIRE.invoke(Clojure.read("com.tickloom.checkers.linearizable"));
        REQUIRE.invoke(Clojure.read("com.tickloom.checkers.sequential"));
        
        CHECK_LINEARIZABLE = Clojure.var("com.tickloom.checkers.linearizable", "check");
        CHECK_SEQUENTIAL = Clojure.var("com.tickloom.checkers.sequential", "check");
    }
    
    /**
     * Check linearizability for built-in models ("register" or "kv")
     */
    public static boolean checkLinearizable(String historyEdn, String model) {
        return checkLinearizable(historyEdn, model, null);
    }
    
    /**
     * Check linearizability with custom options
     */
    public static boolean checkLinearizable(String historyEdn, String model, Map<String, Object> opts) {
        try {
            Object optsMap = opts != null ? clojureMap(opts) : null;
            Object result = CHECK_LINEARIZABLE.invoke(historyEdn, model, optsMap);
            
            if (result instanceof PersistentArrayMap) {
                PersistentArrayMap resultMap = (PersistentArrayMap) result;
                return Boolean.TRUE.equals(resultMap.get(Keyword.intern("valid?")));
            }
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new ConsistencyException("Linearizability check failed", e);
        }
    }
    
    /**
     * Check sequential consistency for built-in models ("register" or "kv")
     */
    public static boolean checkSequential(String historyEdn, String model) {
        return checkSequential(historyEdn, model, null);
    }
    
    /**
     * Check sequential consistency with custom options
     */
    public static boolean checkSequential(String historyEdn, String model, Map<String, Object> opts) {
        try {
            Object optsMap = opts != null ? clojureMap(opts) : null;
            Object result = CHECK_SEQUENTIAL.invoke(historyEdn, model, optsMap);
            
            if (result instanceof PersistentArrayMap) {
                PersistentArrayMap resultMap = (PersistentArrayMap) result;
                return Boolean.TRUE.equals(resultMap.get(Keyword.intern("valid?")));
            }
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new ConsistencyException("Sequential consistency check failed", e);
        }
    }
    
    /**
     * Check linearizability using a custom model object
     */
    public static boolean checkLinearizableWithModel(String historyEdn, Object customModel) {
        return checkLinearizableWithModel(historyEdn, customModel, null);
    }
    
    /**
     * Check linearizability using a custom model object with options
     */
    public static boolean checkLinearizableWithModel(String historyEdn, Object customModel, Map<String, Object> opts) {
        try {
            Object optsMap = opts != null ? clojureMap(opts) : null;
            Object result = CHECK_LINEARIZABLE.invoke(historyEdn, customModel, optsMap);
            
            if (result instanceof PersistentArrayMap) {
                PersistentArrayMap resultMap = (PersistentArrayMap) result;
                return Boolean.TRUE.equals(resultMap.get(Keyword.intern("valid?")));
            }
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new ConsistencyException("Linearizability check with custom model failed", e);
        }
    }
    
    /**
     * Check sequential consistency using a custom model object
     */
    public static boolean checkSequentialWithModel(String historyEdn, Object customModel) {
        return checkSequentialWithModel(historyEdn, customModel, null);
    }
    
    /**
     * Check sequential consistency using a custom model object with options
     */
    public static boolean checkSequentialWithModel(String historyEdn, Object customModel, Map<String, Object> opts) {
        try {
            Object optsMap = opts != null ? clojureMap(opts) : null;
            Object result = CHECK_SEQUENTIAL.invoke(historyEdn, customModel, optsMap);
            
            if (result instanceof PersistentArrayMap) {
                PersistentArrayMap resultMap = (PersistentArrayMap) result;
                return Boolean.TRUE.equals(resultMap.get(Keyword.intern("valid?")));
            }
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new ConsistencyException("Sequential consistency check with custom model failed", e);
        }
    }
    
    /**
     * Check both linearizability and sequential consistency
     */
    public static CheckResult checkBoth(String historyEdn, String model) {
        return checkBoth(historyEdn, model, null);
    }
    
    /**
     * Check both linearizability and sequential consistency with options
     */
    public static CheckResult checkBoth(String historyEdn, String model, Map<String, Object> opts) {
        boolean linearizable = checkLinearizable(historyEdn, model, opts);
        boolean sequential = checkSequential(historyEdn, model, opts);
        
        return CheckResult.builder()
            .linearizable(linearizable)
            .sequential(sequential)
            .modelType(model)
            .build();
    }
    
    /**
     * Check both linearizability and sequential consistency with custom model
     */
    public static CheckResult checkBothWithModel(String historyEdn, Object customModel) {
        return checkBothWithModel(historyEdn, customModel, null);
    }
    
    /**
     * Check both linearizability and sequential consistency with custom model and options
     */
    public static CheckResult checkBothWithModel(String historyEdn, Object customModel, Map<String, Object> opts) {
        boolean linearizable = checkLinearizableWithModel(historyEdn, customModel, opts);
        boolean sequential = checkSequentialWithModel(historyEdn, customModel, opts);
        
        return CheckResult.builder()
            .linearizable(linearizable)
            .sequential(sequential)
            .customModel(customModel.getClass().getSimpleName())
            .build();
    }
    
    /**
     * Convenience methods for specific models
     */
    public static boolean checkRegisterLinearizable(String historyEdn) {
        return checkLinearizable(historyEdn, "register");
    }
    
    public static boolean checkRegisterSequential(String historyEdn) {
        return checkSequential(historyEdn, "register");
    }
    
    public static boolean checkKVLinearizable(String historyEdn) {
        return checkLinearizable(historyEdn, "kv");
    }
    
    public static boolean checkKVSequential(String historyEdn) {
        return checkSequential(historyEdn, "kv");
    }
    
    /**
     * Helper method to convert Java Map to Clojure map
     */
    private static Object clojureMap(Map<String, Object> javaMap) {
        Map<Object, Object> clojureMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : javaMap.entrySet()) {
            clojureMap.put(Keyword.intern(entry.getKey()), entry.getValue());
        }
        return clojureMap;
    }
    
    /**
     * Default options for consistency checking
     */
    public static Map<String, Object> defaultOpts() {
        Map<String, Object> opts = new HashMap<>();
        opts.put("time-limit", 60000);
        return opts;
    }
}
