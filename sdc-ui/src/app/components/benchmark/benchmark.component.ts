import {
  Component,
  OnInit,
  ChangeDetectionStrategy,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { SdcApiService } from '../../services/sdc-api.service';
import { BenchmarkResponse } from '../../models/sdc.models';

export interface BenchmarkRow {
  field: string;
  value: string;
}

type BenchmarkState = 'loading' | 'success' | 'error';

@Component({
  selector: 'app-benchmark',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatCardModule,
    MatProgressBarModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './benchmark.component.html',
  styleUrl: './benchmark.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenchmarkComponent implements OnInit {
  /** Current loading state */
  readonly state = signal<BenchmarkState>('loading');

  /** Error message when the request fails */
  readonly errorMessage = signal<string | null>(null);

  /** Rows derived from the BenchmarkResponse */
  readonly rows = signal<BenchmarkRow[]>([]);

  readonly displayedColumns: string[] = ['field', 'value'];

  constructor(private readonly _apiService: SdcApiService) {}

  ngOnInit(): void {
    this._loadBenchmark();
  }

  retry(): void {
    this.state.set('loading');
    this.errorMessage.set(null);
    this.rows.set([]);
    this._loadBenchmark();
  }

  private _loadBenchmark(): void {
    this._apiService.getBenchmark().subscribe({
      next: (data: BenchmarkResponse) => {
        this.rows.set(this._buildRows(data));
        this.state.set('success');
      },
      error: (err: unknown) => {
        const message = this._extractErrorMessage(err);
        this.errorMessage.set(message);
        this.state.set('error');
      },
    });
  }

  private _buildRows(data: BenchmarkResponse): BenchmarkRow[] {
    const ratioValue = data.compression_ratio === null || data.compression_ratio === undefined
      ? 'N/A'
      : `${data.compression_ratio.toFixed(2)}x`;

    const rows: BenchmarkRow[] = [
      { field: 'Throughput (MB/s)', value: data.throughput_mb_s != null ? `${data.throughput_mb_s.toFixed(1)} MB/s` : 'N/A' },
      { field: 'Compression Ratio', value: ratioValue },
    ];

    if (data['dataset_size_gb'] != null) {
      rows.push({ field: 'Dataset Size (GB)', value: `${(data['dataset_size_gb'] as number).toFixed(2)} GB` });
    }
    if (data['speedup_vs_prior_java_baseline'] != null) {
      rows.push({ field: 'Speedup vs Prior Java Baseline', value: String(data['speedup_vs_prior_java_baseline']) });
    }
    if (data['timestamp'] != null) {
      rows.push({ field: 'Timestamp', value: String(data['timestamp']) });
    }
    if (data['version'] != null) {
      rows.push({ field: 'Version', value: String(data['version']) });
    }
    if (data['reference_hardware'] != null) {
      rows.push({ field: 'Reference Hardware', value: String(data['reference_hardware']) });
    }

    return rows;
  }

  private _extractErrorMessage(err: unknown): string {
    if (err && typeof err === 'object') {
      const httpErr = err as { status?: number; statusText?: string; message?: string };
      if (httpErr.status) {
        return `HTTP ${httpErr.status}: ${httpErr.statusText ?? 'Unknown error'}`;
      }
      if (httpErr.message) {
        return httpErr.message;
      }
    }
    return 'Failed to load benchmark data. Please try again.';
  }
}
