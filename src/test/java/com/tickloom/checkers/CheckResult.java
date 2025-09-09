package com.tickloom.checkers;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Result object for consistency checking operations.
 * Provides rich information about the checking results.
 */
public class CheckResult {
    private final boolean linearizable;
    private final boolean sequential;
    private final String modelType;
    private final String customModelClass;
    private final List<String> errors;
    private final int operationCount;
    
    private CheckResult(Builder builder) {
        this.linearizable = builder.linearizable;
        this.sequential = builder.sequential;
        this.modelType = builder.modelType;
        this.customModelClass = builder.customModelClass;
        this.errors = new ArrayList<>(builder.errors);
        this.operationCount = builder.operationCount;
    }
    
    // Getters
    public boolean isLinearizable() { return linearizable; }
    public boolean isSequential() { return sequential; }
    public String getModelType() { return modelType; }
    public String getCustomModelClass() { return customModelClass; }
    public List<String> getErrors() { return new ArrayList<>(errors); }
    public int getOperationCount() { return operationCount; }
    
    /**
     * Check if both consistency models are satisfied
     */
    public boolean isBothConsistent() {
        return linearizable && sequential;
    }
    
    /**
     * Check if at least one consistency model is satisfied
     */
    public boolean isAnyConsistent() {
        return linearizable || sequential;
    }
    
    /**
     * Fluent validation methods
     */
    public CheckResult requireLinearizable() {
        if (!linearizable) {
            throw new ConsistencyException("History is not linearizable for model: " + 
                (modelType != null ? modelType : customModelClass));
        }
        return this;
    }
    
    public CheckResult requireSequential() {
        if (!sequential) {
            throw new ConsistencyException("History is not sequentially consistent for model: " + 
                (modelType != null ? modelType : customModelClass));
        }
        return this;
    }
    
    public CheckResult requireBoth() {
        return requireLinearizable().requireSequential();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CheckResult{");
        sb.append("linearizable=").append(linearizable);
        sb.append(", sequential=").append(sequential);
        if (modelType != null) {
            sb.append(", modelType='").append(modelType).append("'");
        }
        if (customModelClass != null) {
            sb.append(", customModel='").append(customModelClass).append("'");
        }
        sb.append(", operationCount=").append(operationCount);
        if (!errors.isEmpty()) {
            sb.append(", errors=").append(errors.size());
        }
        sb.append("}");
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CheckResult that = (CheckResult) o;
        return linearizable == that.linearizable &&
               sequential == that.sequential &&
               operationCount == that.operationCount &&
               Objects.equals(modelType, that.modelType) &&
               Objects.equals(customModelClass, that.customModelClass) &&
               Objects.equals(errors, that.errors);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(linearizable, sequential, modelType, customModelClass, errors, operationCount);
    }
    
    /**
     * Builder for CheckResult
     */
    public static class Builder {
        private boolean linearizable = false;
        private boolean sequential = false;
        private String modelType = null;
        private String customModelClass = null;
        private List<String> errors = new ArrayList<>();
        private int operationCount = 0;
        
        public Builder linearizable(boolean linearizable) {
            this.linearizable = linearizable;
            return this;
        }
        
        public Builder sequential(boolean sequential) {
            this.sequential = sequential;
            return this;
        }
        
        public Builder modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }
        
        public Builder customModel(String customModelClass) {
            this.customModelClass = customModelClass;
            return this;
        }
        
        public Builder addError(String error) {
            this.errors.add(error);
            return this;
        }
        
        public Builder errors(List<String> errors) {
            this.errors = new ArrayList<>(errors);
            return this;
        }
        
        public Builder operationCount(int count) {
            this.operationCount = count;
            return this;
        }
        
        public CheckResult build() {
            return new CheckResult(this);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
