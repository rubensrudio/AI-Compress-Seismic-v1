import {
  Component,
  ElementRef,
  ViewChild,
  AfterViewInit,
  OnDestroy,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  signal,
  computed,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

/** Offset constants for SEG-Y Rev1 binary header (0-based from start of binary header block) */
const BINARY_HEADER_OFFSETS = {
  sampleInterval: 16,    // bytes 3217–3218 in file (2 bytes, BE int16)
  samplesPerTrace: 20,   // bytes 3221–3222 in file (2 bytes, BE int16)
  formatCode: 24,        // bytes 3225–3226 in file (2 bytes, BE int16)
} as const;

/** Minimum valid SEG-Y file size: EBCDIC (3200) + binary header (400) */
const SEGY_MIN_SIZE = 3600;

/** Size of EBCDIC textual header in bytes */
const EBCDIC_HEADER_SIZE = 3200;

/** Size of binary file header in bytes */
const BINARY_HEADER_SIZE = 400;

/** Size of trace header in bytes */
const TRACE_HEADER_SIZE = 240;

/** Maximum number of traces to render in the waveform preview */
const MAX_PREVIEW_TRACES = 5;

/** Maximum number of samples to render per waveform trace */
const MAX_SAMPLES_PER_WAVEFORM = 500;

/** EBCDIC to ASCII conversion table (256 entries) */
const EBCDIC_TO_ASCII: number[] = [
  // 0x00–0x0F
  0x00, 0x01, 0x02, 0x03, 0x9C, 0x09, 0x86, 0x7F, 0x97, 0x8D, 0x8E, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
  // 0x10–0x1F
  0x10, 0x11, 0x12, 0x13, 0x9D, 0x0A, 0x08, 0x87, 0x18, 0x19, 0x92, 0x8F, 0x1C, 0x1D, 0x1E, 0x1F,
  // 0x20–0x2F
  0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x17, 0x1B, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x05, 0x06, 0x07,
  // 0x30–0x3F
  0x90, 0x91, 0x16, 0x93, 0x94, 0x95, 0x96, 0x04, 0x98, 0x99, 0x9A, 0x9B, 0x14, 0x15, 0x9E, 0x1A,
  // 0x40–0x4F
  0x20, 0xA0, 0xE2, 0xE4, 0xE0, 0xE1, 0xE3, 0xE5, 0xE7, 0xF1, 0xA2, 0x2E, 0x3C, 0x28, 0x2B, 0x7C,
  // 0x50–0x5F
  0x26, 0xE9, 0xEA, 0xEB, 0xE8, 0xED, 0xEE, 0xEF, 0xEC, 0xDF, 0x21, 0x24, 0x2A, 0x29, 0x3B, 0x5E,
  // 0x60–0x6F
  0x2D, 0x2F, 0xC2, 0xC4, 0xC0, 0xC1, 0xC3, 0xC5, 0xC7, 0xD1, 0xA6, 0x2C, 0x25, 0x5F, 0x3E, 0x3F,
  // 0x70–0x7F
  0xF8, 0xC9, 0xCA, 0xCB, 0xC8, 0xCD, 0xCE, 0xCF, 0xCC, 0x60, 0x3A, 0x23, 0x40, 0x27, 0x3D, 0x22,
  // 0x80–0x8F
  0xD8, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0xAB, 0xBB, 0xF0, 0xFD, 0xFE, 0xB1,
  // 0x90–0x9F
  0xB0, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F, 0x70, 0x71, 0x72, 0xAA, 0xBA, 0xE6, 0xB8, 0xC6, 0xA4,
  // 0xA0–0xAF
  0xB5, 0x7E, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0xA1, 0xBF, 0xD0, 0x5B, 0xDE, 0xAE,
  // 0xB0–0xBF
  0xAC, 0xA3, 0xA5, 0xB7, 0xA9, 0xA7, 0xBC, 0xBC, 0xBD, 0xBE, 0xDD, 0xA8, 0xAF, 0x5D, 0xB4, 0xD7,
  // 0xC0–0xCF
  0x7B, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0xAD, 0xF4, 0xF6, 0xF2, 0xF3, 0xF5,
  // 0xD0–0xDF
  0x7D, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x51, 0x52, 0xB9, 0xFB, 0xFC, 0xF9, 0xFA, 0xFF,
  // 0xE0–0xEF
  0x5C, 0xF7, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0xB2, 0xD4, 0xD6, 0xD2, 0xD3, 0xD5,
  // 0xF0–0xFF
  0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0xB3, 0xDB, 0xDC, 0xD9, 0xDA, 0x9F,
];

export interface BinaryHeaderField {
  field: string;
  value: number | string;
  unit: string;
}

export interface WaveformTrace {
  traceIndex: number;
  points: string;      // SVG polyline points string
  width: number;
  height: number;
}

@Component({
  selector: 'app-file-inspector',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './file-inspector.component.html',
  styleUrl: './file-inspector.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FileInspectorComponent implements AfterViewInit, OnDestroy {
  @ViewChild('fileInput') fileInputRef?: ElementRef<HTMLInputElement>;

  /** Whether a file is currently being parsed */
  readonly loading = signal(false);

  /** User-facing error message, null when no error */
  readonly errorMessage = signal<string | null>(null);

  /** Decoded EBCDIC header text (40 lines of 80 chars) */
  readonly ebcdicText = signal<string | null>(null);

  /** Rows for the binary header mat-table */
  readonly binaryHeaderRows = signal<BinaryHeaderField[]>([]);

  /** Waveform traces for SVG preview */
  readonly waveformTraces = signal<WaveformTrace[]>([]);

  /** True when a file has been successfully parsed */
  readonly hasParsedFile = computed(
    () => this.ebcdicText() !== null && this.binaryHeaderRows().length > 0
  );

  readonly displayedColumns: string[] = ['field', 'value', 'unit'];

  /** Flag to track drag-over state for CSS styling */
  isDragOver = false;

  private _dragCounter = 0;

  constructor(private readonly _snackBar: MatSnackBar, private readonly _cdr: ChangeDetectorRef) {}

  ngAfterViewInit(): void {
    // Nothing to initialize — view child is used lazily
  }

  ngOnDestroy(): void {
    // No subscriptions to clean up in this component
  }

  /** Opens the native file picker */
  openFilePicker(): void {
    this.fileInputRef?.nativeElement.click();
  }

  /** Handles the native file input change event */
  onFileInputChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this._processFile(input.files[0]);
      // Reset so the same file can be re-selected
      input.value = '';
    }
  }

  /** Drag-and-drop event handlers */
  onDragEnter(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this._dragCounter++;
    this.isDragOver = true;
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this._dragCounter--;
    if (this._dragCounter === 0) {
      this.isDragOver = false;
    }
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
    this._dragCounter = 0;

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this._processFile(files[0]);
    }
  }

  /** Core file processing: reads ArrayBuffer, parses headers, draws waveform */
  private _processFile(file: File): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.ebcdicText.set(null);
    this.binaryHeaderRows.set([]);
    this.waveformTraces.set([]);

    const reader = new FileReader();

    reader.onload = (e: ProgressEvent<FileReader>) => {
      const buffer = e.target?.result as ArrayBuffer | null;
      if (!buffer) {
        this._setError('Falha ao ler o arquivo.');
        return;
      }

      if (buffer.byteLength < SEGY_MIN_SIZE) {
        this._setError(
          `Arquivo muito pequeno para ser um SEG-Y Rev1 válido. ` +
          `Tamanho: ${buffer.byteLength} bytes (mínimo: ${SEGY_MIN_SIZE} bytes).`
        );
        return;
      }

      try {
        const bytes = new Uint8Array(buffer);

        // 1. Parse EBCDIC header (first 3200 bytes)
        const ebcdic = this._decodeEbcdic(bytes.subarray(0, EBCDIC_HEADER_SIZE));
        this.ebcdicText.set(ebcdic);

        // 2. Parse binary header (bytes 3200–3599)
        const binHeader = new DataView(buffer, EBCDIC_HEADER_SIZE, BINARY_HEADER_SIZE);
        const sampleInterval = binHeader.getInt16(BINARY_HEADER_OFFSETS.sampleInterval, false); // big-endian
        const samplesPerTrace = binHeader.getInt16(BINARY_HEADER_OFFSETS.samplesPerTrace, false);
        const formatCode = binHeader.getInt16(BINARY_HEADER_OFFSETS.formatCode, false);

        // Estimated trace count from file size (format code 5 = 4 bytes/sample; default if unknown)
        const bytesPerSample = formatCode === 1 ? 4 : 4; // IBM float32 or IEEE float32
        const traceDataSize = TRACE_HEADER_SIZE + samplesPerTrace * bytesPerSample;
        const traceCount =
          samplesPerTrace > 0 && traceDataSize > 0
            ? Math.floor((buffer.byteLength - SEGY_MIN_SIZE) / traceDataSize)
            : 0;

        this.binaryHeaderRows.set([
          { field: 'Samples per Trace', value: samplesPerTrace > 0 ? samplesPerTrace : 'N/A', unit: 'samples' },
          { field: 'Sample Interval', value: sampleInterval > 0 ? sampleInterval : 'N/A', unit: 'μs' },
          { field: 'Format Code', value: formatCode > 0 ? formatCode : 'N/A', unit: formatCode === 1 ? '(IBM float)' : formatCode === 5 ? '(IEEE float)' : '' },
          { field: 'Trace Count (estimated)', value: traceCount, unit: 'traces' },
        ]);

        // 3. Waveform preview — read first MAX_PREVIEW_TRACES traces (format code 5 = IEEE float32 BE)
        if (samplesPerTrace > 0 && buffer.byteLength > SEGY_MIN_SIZE) {
          this.waveformTraces.set(
            this._buildWaveformTraces(buffer, samplesPerTrace, traceCount, formatCode)
          );
        }

        this.loading.set(false);
        this._cdr.markForCheck();
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : String(err);
        this._setError(`Erro ao parsear arquivo SEG-Y: ${message}`);
      }
    };

    reader.onerror = () => {
      this._setError('Erro ao ler o arquivo. Verifique se o arquivo não está corrompido.');
    };

    reader.readAsArrayBuffer(file);
  }

  /** Converts EBCDIC bytes to a printable ASCII string (40 lines × 80 chars) */
  private _decodeEbcdic(bytes: Uint8Array): string {
    const chars: string[] = [];
    for (let i = 0; i < bytes.length; i++) {
      const ascii = EBCDIC_TO_ASCII[bytes[i]] ?? 0x20;
      // Replace non-printable with space, except for 0x0A (LF) which we inject at line breaks
      if (i > 0 && i % 80 === 0) {
        chars.push('\n');
      }
      chars.push(ascii >= 0x20 && ascii < 0x7F ? String.fromCharCode(ascii) : ' ');
    }
    return chars.join('');
  }

  /** Builds waveform SVG polyline point strings for the first N traces */
  private _buildWaveformTraces(
    buffer: ArrayBuffer,
    samplesPerTrace: number,
    traceCount: number,
    formatCode: number
  ): WaveformTrace[] {
    const traces: WaveformTrace[] = [];
    const numTraces = Math.min(traceCount, MAX_PREVIEW_TRACES);
    const svgWidth = 320;
    const svgHeight = 80;

    for (let t = 0; t < numTraces; t++) {
      const traceOffset = SEGY_MIN_SIZE + t * (TRACE_HEADER_SIZE + samplesPerTrace * 4);
      const sampleOffset = traceOffset + TRACE_HEADER_SIZE;

      if (sampleOffset + samplesPerTrace * 4 > buffer.byteLength) {
        break;
      }

      // Read samples as Float32 big-endian (format code 5) or attempt IBM float approximation
      const samples = this._readTraceSamples(buffer, sampleOffset, samplesPerTrace, formatCode);

      // Normalize and build polyline points
      const points = this._buildPolylinePoints(samples, svgWidth, svgHeight);
      traces.push({ traceIndex: t + 1, points, width: svgWidth, height: svgHeight });
    }

    return traces;
  }

  /** Reads trace samples; supports format code 5 (IEEE float) and falls back for others */
  private _readTraceSamples(
    buffer: ArrayBuffer,
    byteOffset: number,
    samplesPerTrace: number,
    formatCode: number
  ): number[] {
    const count = Math.min(samplesPerTrace, MAX_SAMPLES_PER_WAVEFORM);
    const samples: number[] = new Array(count);

    if (formatCode === 5) {
      // IEEE 754 float32, big-endian
      const view = new DataView(buffer, byteOffset, count * 4);
      for (let i = 0; i < count; i++) {
        samples[i] = view.getFloat32(i * 4, false);
      }
    } else if (formatCode === 1) {
      // IBM float32, big-endian — convert to IEEE
      const view = new DataView(buffer, byteOffset, count * 4);
      for (let i = 0; i < count; i++) {
        samples[i] = this._ibmFloat32ToIeee(view.getUint32(i * 4, false));
      }
    } else {
      // Fallback: treat as IEEE float32 big-endian for preview purposes
      const view = new DataView(buffer, byteOffset, count * 4);
      for (let i = 0; i < count; i++) {
        samples[i] = view.getFloat32(i * 4, false);
      }
    }

    return samples;
  }

  /** Converts an IBM 360 float32 (big-endian uint32) to a JavaScript number */
  private _ibmFloat32ToIeee(ibm: number): number {
    if (ibm === 0) return 0;
    const sign = (ibm & 0x80000000) ? -1 : 1;
    const exponent = ((ibm >> 24) & 0x7F) - 64;
    const mantissa = (ibm & 0x00FFFFFF) / 0x1000000;
    return sign * mantissa * Math.pow(16, exponent);
  }

  /** Converts sample array to SVG polyline points, normalizing amplitude to [0, svgHeight] */
  private _buildPolylinePoints(samples: number[], width: number, height: number): string {
    if (samples.length === 0) return '';

    // Find min/max for normalization
    let min = samples[0];
    let max = samples[0];
    for (const s of samples) {
      if (s < min) min = s;
      if (s > max) max = s;
    }

    const range = max - min;
    const padding = 4; // px top/bottom

    const points: string[] = [];
    const n = samples.length;
    for (let i = 0; i < n; i++) {
      const x = Math.round((i / Math.max(n - 1, 1)) * width);
      let y: number;
      if (range === 0) {
        y = Math.round(height / 2);
      } else {
        y = Math.round(padding + ((max - samples[i]) / range) * (height - 2 * padding));
      }
      points.push(`${x},${y}`);
    }

    return points.join(' ');
  }

  private _setError(message: string): void {
    this.errorMessage.set(message);
    this.loading.set(false);
    this._snackBar.open(message, 'Fechar', { duration: 5000 });
    this._cdr.markForCheck();
  }
}
