export interface HealthResponse {
  status: string;
  timestamp?: string;
}

export interface BenchmarkResponse {
  compression_ratio: number;
  throughput_mbps: number;
  [key: string]: unknown;
}
