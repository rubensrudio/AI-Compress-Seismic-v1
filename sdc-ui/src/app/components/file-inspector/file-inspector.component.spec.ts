import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  flush,
  tick,
} from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';

import {
  FileInspectorComponent,
  BinaryHeaderField,
  WaveformTrace,
} from './file-inspector.component';

// ─── Helpers ────────────────────────────────────────────────────────────────

/**
 * Builds a minimal valid SEG-Y Rev1 ArrayBuffer for testing.
 *
 * Layout:
 *  - 3200 bytes EBCDIC header (filled with EBCDIC 'A' = 0xC1)
 *  - 400 bytes binary header  (all zeros, then patched)
 *  - 1 trace: 240 bytes trace header + samplesPerTrace * 4 bytes IEEE float32 BE
 */
function buildMinimalSegyBuffer(
  samplesPerTrace: number = 10,
  sampleIntervalUs: number = 2000,
  formatCode: number = 5
): ArrayBuffer {
  const traceDataBytes = samplesPerTrace * 4;
  const totalSize = 3200 + 400 + 240 + traceDataBytes;
  const buffer = new ArrayBuffer(totalSize);
  const bytes = new Uint8Array(buffer);

  // EBCDIC header: fill with EBCDIC 'A' (0xC1)
  bytes.fill(0xC1, 0, 3200);

  // Binary header: patch required fields (big-endian int16)
  const binView = new DataView(buffer, 3200, 400);
  binView.setInt16(16, sampleIntervalUs, false);   // sample interval
  binView.setInt16(20, samplesPerTrace, false);    // samples per trace
  binView.setInt16(24, formatCode, false);         // format code

  // Trace data: write ascending float32 BE values (1.0, 2.0, ...)
  const sampleView = new DataView(buffer, 3200 + 400 + 240, traceDataBytes);
  for (let i = 0; i < samplesPerTrace; i++) {
    sampleView.setFloat32(i * 4, i + 1.0, false); // big-endian
  }

  return buffer;
}

/** Converts an ArrayBuffer to a File object */
function bufferToFile(buffer: ArrayBuffer, name = 'test.segy'): File {
  return new File([buffer], name, { type: 'application/octet-stream' });
}

// ─── Synchronous FileReader mock ─────────────────────────────────────────────
//
// We install it as a replacement for window.FileReader so that _processFile()
// immediately invokes `onload` / `onerror` without any async boundary.
// This avoids the "timers still in queue" fakeAsync error caused by
// Promise-based scheduling inside real FileReader callbacks.

function installMockFileReader(
  buffer: ArrayBuffer | null,
  triggerError = false
): void {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (window as any).FileReader = class MockFileReader {
    onload?: (e: ProgressEvent<FileReader>) => void;
    onerror?: (e: ProgressEvent<FileReader>) => void;

    readAsArrayBuffer(_file: File): void {
      // Dispatch synchronously so fakeAsync / tick can track everything
      if (triggerError) {
        this.onerror?.({} as ProgressEvent<FileReader>);
      } else {
        this.onload?.({
          target: { result: buffer } as unknown as FileReader,
        } as ProgressEvent<FileReader>);
      }
    }
  };
}

// ─── Spec ────────────────────────────────────────────────────────────────────

describe('FileInspectorComponent', () => {
  let fixture: ComponentFixture<FileInspectorComponent>;
  let component: FileInspectorComponent;
  // Keep a reference to the original FileReader so we can restore it
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let originalFileReader: any;

  beforeEach(async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    originalFileReader = (window as any).FileReader;

    await TestBed.configureTestingModule({
      imports: [FileInspectorComponent, NoopAnimationsModule],
      providers: [
        { provide: MatSnackBar, useValue: { open: jasmine.createSpy('open') } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(FileInspectorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    // Restore the real FileReader after every test
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).FileReader = originalFileReader;
  });

  // ── 1. Basic instantiation ─────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render the drop zone', () => {
    const el: HTMLElement = fixture.nativeElement;
    const dropZone = el.querySelector('.drop-zone');
    expect(dropZone).toBeTruthy();
  });

  it('should start with loading false and no parsed data', () => {
    expect(component.loading()).toBeFalse();
    expect(component.errorMessage()).toBeNull();
    expect(component.ebcdicText()).toBeNull();
    expect(component.binaryHeaderRows()).toEqual([]);
    expect(component.waveformTraces()).toEqual([]);
    expect(component.hasParsedFile()).toBeFalse();
  });

  // ── 2. Successful parse ────────────────────────────────────────────────────

  it('should parse a minimal valid SEG-Y buffer and expose ebcdicText', fakeAsync(() => {
    const buffer = buildMinimalSegyBuffer(10, 2000, 5);
    installMockFileReader(buffer);

    const file = bufferToFile(buffer);
    const fakeEvent = { target: { files: [file], value: '' } } as unknown as Event;
    component.onFileInputChange(fakeEvent);
    flush(); // drain all pending timers (MatSnackBar etc.)
    fixture.detectChanges();

    expect(component.loading()).toBeFalse();
    expect(component.errorMessage()).toBeNull();

    const text = component.ebcdicText();
    expect(text).not.toBeNull();
    // EBCDIC 0xC1 maps to ASCII 'A' (0x41)
    expect(text).toContain('A');
  }));

  it('should populate binaryHeaderRows after parsing a valid buffer', fakeAsync(() => {
    const buffer = buildMinimalSegyBuffer(20, 4000, 5);
    installMockFileReader(buffer);

    const file = bufferToFile(buffer);
    const fakeEvent = { target: { files: [file], value: '' } } as unknown as Event;
    component.onFileInputChange(fakeEvent);
    flush();
    fixture.detectChanges();

    const rows: BinaryHeaderField[] = component.binaryHeaderRows();
    expect(rows.length).toBe(4);

    const sptRow = rows.find(r => r.field === 'Samples per Trace');
    expect(sptRow?.value).toBe(20);

    const siRow = rows.find(r => r.field === 'Sample Interval');
    expect(siRow?.value).toBe(4000);

    const fcRow = rows.find(r => r.field === 'Format Code');
    expect(fcRow?.value).toBe(5);
  }));

  it('should generate waveform traces for a valid file', fakeAsync(() => {
    const buffer = buildMinimalSegyBuffer(10, 2000, 5);
    installMockFileReader(buffer);

    const file = bufferToFile(buffer);
    const fakeEvent = { target: { files: [file], value: '' } } as unknown as Event;
    component.onFileInputChange(fakeEvent);
    flush();
    fixture.detectChanges();

    const traces: WaveformTrace[] = component.waveformTraces();
    expect(traces.length).toBeGreaterThanOrEqual(1);
    expect(traces[0].traceIndex).toBe(1);
    expect(traces[0].points).toBeTruthy();
  }));

  it('should set hasParsedFile to true after a successful parse', fakeAsync(() => {
    const buffer = buildMinimalSegyBuffer(10, 2000, 5);
    installMockFileReader(buffer);

    const file = bufferToFile(buffer);
    const fakeEvent = { target: { files: [file], value: '' } } as unknown as Event;
    component.onFileInputChange(fakeEvent);
    flush();
    fixture.detectChanges();

    expect(component.hasParsedFile()).toBeTrue();
  }));

  // ── 3. Error paths ────────────────────────────────────────────────────────

  it('should set errorMessage when file is smaller than SEG-Y minimum', fakeAsync(() => {
    const tinyBuffer = new ArrayBuffer(100); // < 3600 bytes
    installMockFileReader(tinyBuffer);

    const file = bufferToFile(tinyBuffer, 'tiny.segy');
    const fakeEvent = { target: { files: [file], value: '' } } as unknown as Event;
    component.onFileInputChange(fakeEvent);
    // Advance past any pending snack-bar hide timers (5000 ms + margin)
    tick(10000);
    flush();
    fixture.detectChanges();

    expect(component.errorMessage()).not.toBeNull();
    expect(component.errorMessage()).toContain('muito pequeno');
    expect(component.loading()).toBeFalse();
    expect(component.ebcdicText()).toBeNull();
  }));

  it('should set errorMessage on FileReader error', fakeAsync(() => {
    installMockFileReader(null, /* triggerError */ true);

    const file = bufferToFile(new ArrayBuffer(4000));
    const fakeEvent = { target: { files: [file], value: '' } } as unknown as Event;
    component.onFileInputChange(fakeEvent);
    // Advance past any pending snack-bar hide timers (5000 ms + margin)
    tick(10000);
    flush();
    fixture.detectChanges();

    expect(component.errorMessage()).not.toBeNull();
    expect(component.loading()).toBeFalse();
  }));

  // ── 4. Drag-and-drop helpers ───────────────────────────────────────────────

  it('should set isDragOver on dragenter and clear on dragleave', () => {
    const enterEvent = new DragEvent('dragenter');
    component.onDragEnter(enterEvent);
    expect(component.isDragOver).toBeTrue();

    const leaveEvent = new DragEvent('dragleave');
    component.onDragLeave(leaveEvent);
    expect(component.isDragOver).toBeFalse();
  });

  it('should not throw on dragover', () => {
    expect(() =>
      component.onDragOver(new DragEvent('dragover'))
    ).not.toThrow();
  });

  // ── 5. No-file-in-event guard ─────────────────────────────────────────────

  it('should do nothing if onFileInputChange is called with no files', () => {
    const fakeEvent = { target: { files: null, value: '' } } as unknown as Event;
    expect(() => component.onFileInputChange(fakeEvent)).not.toThrow();
    expect(component.loading()).toBeFalse();
  });
});
