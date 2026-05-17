export interface HealthResponse {
  status: string;
  timestamp?: string;
  codec?: string;
  model?: string;
}

export interface BenchmarkResponse {
  compression_ratio: number | null;
  throughput_mb_s: number;
  [key: string]: unknown;
}
