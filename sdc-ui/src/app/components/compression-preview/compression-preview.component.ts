import {
  Component,
  Input,
  OnChanges,
  SimpleChanges,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  signal,
  computed,
  OnDestroy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subscription } from 'rxjs';
import { SdcApiService } from '../../services/sdc-api.service';
import { BenchmarkResponse } from '../../models/sdc.models';

/** Component state machine */
export type PreviewState = 'idle' | 'loading-benchmark' | 'ready' | 'compressing' | 'done' | 'error';

@Component({
  selector: 'app-compression-preview',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
  ],
  templateUrl: './compression-preview.component.html',
  styleUrl: './compression-preview.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompressionPreviewComponent implements OnChanges, OnDestroy {
  /** The SEG-Y file received from the parent (FileInspectorComponent) */
  @Input() file: File | null = null;

  /** Current UI state */
  readonly state = signal<PreviewState>('idle');

  /** Benchmark response holding estimated compression_ratio */
  readonly benchmarkData = signal<BenchmarkResponse | null>(null);

  /** Error message for the error state */
  readonly errorMessage = signal<string | null>(null);

  /** Real ratio calculated after successful compression */
  readonly realRatio = signal<number | null>(null);

  /** File size in bytes, kept in sync with @Input file */
  readonly fileSize = signal<number | null>(null);

  /** True while a progress bar should be shown (indeterminate) */
  readonly showProgress = computed(
    () => this.state() === 'loading-benchmark' || this.state() === 'compressing'
  );

  /** Formatted file size string (e.g. "12.34 MB") */
  readonly fileSizeLabel = computed(() => {
    const size = this.fileSize();
    if (size === null) return null;
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(2)} KB`;
    return `${(size / (1024 * 1024)).toFixed(2)} MB`;
  });

  /** Formatted estimated ratio or 'N/A' when null */
  readonly estimatedRatioLabel = computed(() => {
    const data = this.benchmarkData();
    if (data === null) return null;
    if (data.compression_ratio === null || data.compression_ratio === undefined) return 'N/A';
    return `${data.compression_ratio.toFixed(1)}x`;
  });

  /** Formatted real ratio after compression */
  readonly realRatioLabel = computed(() => {
    const ratio = this.realRatio();
    if (ratio === null) return null;
    return `${ratio.toFixed(2)}x`;
  });

  private _subscription: Subscription | null = null;

  constructor(
    private readonly _apiService: SdcApiService,
    private readonly _cdr: ChangeDetectorRef
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['file']) {
      const newFile: File | null = changes['file'].currentValue;
      this._onFileChange(newFile);
    }
  }

  ngOnDestroy(): void {
    this._subscription?.unsubscribe();
  }

  /** Initiates the compression request */
  compress(): void {
    if (!this.file || this.state() === 'compressing') {
      return;
    }

    this._subscription?.unsubscribe();
    this.state.set('compressing');
    this.errorMessage.set(null);
    this.realRatio.set(null);

    const originalSize = this.file.size;

    this._subscription = this._apiService.compress(this.file).subscribe({
      next: (blob: Blob) => {
        // Calculate real compression ratio
        const realRatio = originalSize > 0 && blob.size > 0
          ? originalSize / blob.size
          : null;
        this.realRatio.set(realRatio);

        // Trigger browser download
        this._downloadBlob(blob, this._buildOutputFilename(this.file!.name));

        this.state.set('done');
        this._cdr.markForCheck();
      },
      error: (err: unknown) => {
        const message = this._extractErrorMessage(err);
        this.errorMessage.set(message);
        this.state.set('error');
        this._cdr.markForCheck();
      },
    });
  }

  /** Resets the component to allow re-upload */
  reset(): void {
    this._subscription?.unsubscribe();
    this.state.set('idle');
    this.errorMessage.set(null);
    this.realRatio.set(null);
    this.benchmarkData.set(null);
    this.fileSize.set(null);
  }

  // ──────────────────────────────────────────────
  // Private helpers
  // ──────────────────────────────────────────────

  private _onFileChange(file: File | null): void {
    this._subscription?.unsubscribe();
    this.errorMessage.set(null);
    this.realRatio.set(null);

    if (!file) {
      this.state.set('idle');
      this.fileSize.set(null);
      this.benchmarkData.set(null);
      return;
    }

    this.fileSize.set(file.size);
    this.state.set('loading-benchmark');
    this.benchmarkData.set(null);

    this._subscription = this._apiService.getBenchmark().subscribe({
      next: (data: BenchmarkResponse) => {
        this.benchmarkData.set(data);
        this.state.set('ready');
        this._cdr.markForCheck();
      },
      error: (err: unknown) => {
        // Benchmark error is non-fatal — still allow compression but show N/A
        this.benchmarkData.set({ compression_ratio: null, throughput_mb_s: 0 });
        this.state.set('ready');
        // Log for developer awareness but don't surface as blocking error
        console.warn('CompressionPreviewComponent: getBenchmark() failed', err);
        this._cdr.markForCheck();
      },
    });
  }

  private _downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.style.display = 'none';
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    // Revoke after a short delay to allow the browser to initiate the download
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  private _buildOutputFilename(originalName: string): string {
    // Replace .segy or .sgy extension with .sdc; keep stem as-is
    return originalName.replace(/\.(segy|sgy|sgy2)$/i, '') + '.sdc';
  }

  private _extractErrorMessage(err: unknown): string {
    if (err && typeof err === 'object') {
      const httpErr = err as { status?: number; statusText?: string; message?: string };
      if (httpErr.status) {
        return `Erro HTTP ${httpErr.status}: ${httpErr.statusText ?? 'Erro desconhecido'}`;
      }
      if (httpErr.message) {
        return httpErr.message;
      }
    }
    return 'Erro desconhecido ao comprimir o arquivo. Tente novamente.';
  }
}
