package address.sync;

class RateLimitStatus {
    private int quotaLimit;
    private int quotaRemaining;
    private long quotaReset;

    RateLimitStatus(int quotaLimit, int quotaRemaining, long quotaReset) {
        this.quotaLimit = quotaLimit;
        this.quotaRemaining = quotaRemaining;
        this.quotaReset = quotaReset;
    }

    RateLimitStatus() {
    }

    public int getQuotaLimit() {
        return quotaLimit;
    }

    void setQuotaLimit(int quotaLimit) {
        this.quotaLimit = quotaLimit;
    }

    public int getQuotaRemaining() {
        return quotaRemaining;
    }

    void setQuotaRemaining(int quotaRemaining) {
        this.quotaRemaining = quotaRemaining;
    }

    public long getQuotaReset() {
        return quotaReset;
    }

    void setQuotaResetTime(long quotaResetTime) {
        this.quotaReset = quotaResetTime;
    }

    public void useQuota(int amount) {
        quotaRemaining -= amount;
    }
}