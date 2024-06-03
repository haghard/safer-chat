package server.grpc;


import java.lang.management.ManagementFactory;
import java.util.List;

//import com.oracle.svm.core.util.ExitStatus;
//import com.oracle.svm.driver.NativeImage.NativeImageError;

//https://github.com/oracle/graal/blob/259b37b8d731f52fdc8ce2720a0355b7b871dfbd/substratevm/src/com.oracle.svm.driver/src/com/oracle/svm/driver/MemoryUtil.java#L55

class MemoryUtil2 {

  private static final long KiB_TO_BYTES = 1024L;
  private static final long MiB_TO_BYTES = 1024L * KiB_TO_BYTES;
  private static final long GiB_TO_BYTES = 1024L * MiB_TO_BYTES;

  /* Builder needs at least 512MiB for building a helloworld in a reasonable amount of time. */
  private static final long MIN_HEAP_BYTES = 512L * MiB_TO_BYTES;

  /* If free memory is below 8GiB, use 85% of total system memory (e.g., 7GiB * 85% ~ 6GiB). */
  private static final long DEDICATED_MODE_THRESHOLD = 8L * GiB_TO_BYTES;
  private static final double DEDICATED_MODE_TOTAL_MEMORY_RATIO = 0.85D;

  /*
   * Builder uses at most 32GB to avoid disabling compressed oops (UseCompressedOops).
   * Deliberately use GB (not GiB) to stay well below 32GiB when relative maximum is calculated.
   */
  private static final long MAX_HEAP_BYTES = 32_000_000_000L;

  public static List<String> determineMemoryFlags() {
    return List.of(
      /*
       * Use MaxRAMPercentage to allow users to overwrite max heap setting with
       * -XX:MaxRAMPercentage or -Xmx, and freely adjust the min heap with
       * -XX:InitialRAMPercentage or -Xms.
       */
      "-XX:MaxRAMPercentage=" + determineReasonableMaxRAMPercentage(),
      /*
       * Optimize for throughput by increasing the goal of the total time for
       * garbage collection from 1% to 10% (N=9). This also reduces peak RSS.
       */
      "-XX:GCTimeRatio=9", // 1/(1+N) time for GC
      /*
       * Let builder exit on first OutOfMemoryError to provide for shorter
       * feedback loops.
       */
      "-XX:+ExitOnOutOfMemoryError");
  }

  /**
   * Returns a percentage (0.0-100.0) to be used as a value for the -XX:MaxRAMPercentage flag of
   * the builder process. Prefer free memory over total memory to reduce memory pressure on the
   * host machine. Note that this method uses OperatingSystemMXBean, which is container-aware.
   */
  static double determineReasonableMaxRAMPercentage() {
    var osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    final double totalMemorySize = osBean.getTotalMemorySize();
    double reasonableMaxMemorySize = osBean.getFreeMemorySize();

    if (reasonableMaxMemorySize < DEDICATED_MODE_THRESHOLD) {
      /*
       * When free memory is low, for example in memory-constrained environments or when a
       * good amount of memory is used for caching, use a fixed percentage of total memory
       * rather than free memory. In containerized environments, builds are expected to run
       * more or less exclusively (builder + driver + optional Gradle/Maven process).
       */
      reasonableMaxMemorySize = totalMemorySize * DEDICATED_MODE_TOTAL_MEMORY_RATIO;
    }

    if (reasonableMaxMemorySize < MIN_HEAP_BYTES) {
      /*throw new NativeImageError(
        "There is not enough memory available on the system (got %sMiB, need at least %sMiB). Consider freeing up memory if builds are slow, for example, by closing applications that you do not need."
          .formatted(reasonableMaxMemorySize / MiB_TO_BYTES, MIN_HEAP_BYTES / MiB_TO_BYTES),
        null, ExitStatus.OUT_OF_MEMORY.getValue());*/
      throw new RuntimeException(
        "There is not enough memory available on the system (got %sMiB, need at least %sMiB). Consider freeing up memory if builds are slow, for example, by closing applications that you do not need."
          .formatted(reasonableMaxMemorySize / MiB_TO_BYTES, MIN_HEAP_BYTES / MiB_TO_BYTES));

    }

    /* Ensure max memory size does not exceed upper limit. */
    reasonableMaxMemorySize = Math.min(reasonableMaxMemorySize, MAX_HEAP_BYTES);

    return reasonableMaxMemorySize / totalMemorySize * 100;
  }
}