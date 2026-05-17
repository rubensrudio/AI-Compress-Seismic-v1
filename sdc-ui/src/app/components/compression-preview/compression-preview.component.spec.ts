import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController
} from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { SimpleChange } from '@angular/core';
import { CompressionPreviewComponent } from './compression-preview.component';
import { SdcApiService } from '../../services/sdc-api.service';

/** Helper: create a minimal File object for testing */
function makeFile(name = 'test.segy', sizeBytes = 1024 * 1024): File {
  const content = new Uint8Array(sizeBytes);
  return new File([content], name, { type: 'application/octet-stream' });
}

/** Helper: create a Blob simulating the .sdc output */
function makeSdcBlob(sizeBytes = 256 * 1024): Blob {
  return new Blob([new Uint8Array(sizeBytes)], { type: 'application/octet-stream' });
}

/**
 * Sets the @Input() file on the component and triggers ngOnChanges,
 * as Angular does not call ngOnChanges automatically when properties are
 * assigned directly in tests.
 */
function setFile(
  component: CompressionPreviewComponent,
  file: File | null,
  previousValue: File | null = null
): void {
  component.file = file;
  component.ngOnChanges({
    file: new SimpleChange(previousValue, file, previousValue === null && file !== null)
  });
}

describe('CompressionPreviewComponent', () => {
  let fixture: ComponentFixture<CompressionPreviewComponent>;
  let component: CompressionPreviewComponent;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        CompressionPreviewComponent,
        HttpClientTestingModule,
        NoopAnimationsModule,
      ],
      providers: [SdcApiService],
    }).compileComponents();

    fixture = TestBed.createComponent(CompressionPreviewComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ──────────────────────────────────────────────
  // 1. Initial render without a file
  // ──────────────────────────────────────────────

  it('should create and render in idle state without error', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
    expect(component.state()).toBe('idle');
    // No pending HTTP requests when no file is set
    httpMock.expectNone('/api/benchmark');
  });

  it('should display empty-state message when no file is provided', () => {
    fixture.detectChanges();
    const emptyEl = fixture.debugElement.query(By.css('.empty-state'));
    expect(emptyEl).toBeTruthy();
  });

  it('should NOT display file info rows when no file is provided', () => {
    fixture.detectChanges();
    const fileInfo = fixture.debugElement.query(By.css('.file-info'));
    expect(fileInfo).toBeNull();
  });

  // ──────────────────────────────────────────────
  // 2. With @Input() file set — getBenchmark() called and ratio displayed
  // ──────────────────────────────────────────────

  it('should call GET /api/benchmark when a file is set and transition to ready', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile());
    fixture.detectChanges();

    // Should be in loading-benchmark state while waiting
    expect(component.state()).toBe('loading-benchmark');

    // Flush the benchmark request
    const req = httpMock.expectOne('/api/benchmark');
    expect(req.request.method).toBe('GET');
    req.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('ready');
    expect(component.benchmarkData()).not.toBeNull();
    expect(component.benchmarkData()?.compression_ratio).toBe(3.5);
  }));

  it('should display the estimated compression ratio after getBenchmark() resolves', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile());
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    // Look for the estimated ratio value in the DOM
    const valueEls = fixture.debugElement.queryAll(By.css('.info-row__value--highlight'));
    const ratioEl = valueEls.find(el =>
      (el.nativeElement as HTMLElement).textContent?.includes('3.5x')
    );
    expect(ratioEl).toBeTruthy();
  }));

  it('should display file size in the info panel after benchmark loads', fakeAsync(() => {
    fixture.detectChanges();

    const file = makeFile('sample.segy', 2 * 1024 * 1024); // 2 MB
    setFile(component, file);
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: 4.0, throughput_mb_s: 80 });
    tick();
    fixture.detectChanges();

    const fileInfo = fixture.debugElement.query(By.css('.file-info'));
    expect(fileInfo).toBeTruthy();
    // File name should appear
    const hostText = (fileInfo.nativeElement as HTMLElement).textContent ?? '';
    expect(hostText).toContain('sample.segy');
  }));

  it('should display compression_ratio as N/A when benchmark returns null ratio', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile());
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: null, throughput_mb_s: 0 });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('ready');
    expect(component.estimatedRatioLabel()).toBe('N/A');

    // DOM should show N/A
    const highlightEls = fixture.debugElement.queryAll(By.css('.info-row__value--highlight'));
    const naEl = highlightEls.find(el =>
      (el.nativeElement as HTMLElement).textContent?.includes('N/A')
    );
    expect(naEl).toBeTruthy();
  }));

  it('should still reach ready state when getBenchmark() fails (non-blocking error)', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile());
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush('Internal Server Error', { status: 500, statusText: 'Internal Server Error' });
    tick();
    fixture.detectChanges();

    // Non-blocking: state becomes 'ready' with null ratio
    expect(component.state()).toBe('ready');
    expect(component.estimatedRatioLabel()).toBe('N/A');
  }));

  // ──────────────────────────────────────────────
  // 3. Compress button — POST fired, download triggered
  // ──────────────────────────────────────────────

  it('should call POST /api/compress when compress() is invoked', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile());
    fixture.detectChanges();

    // Resolve benchmark first
    const benchmarkReq = httpMock.expectOne('/api/benchmark');
    benchmarkReq.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    // Spy on download helper to avoid actual DOM manipulation in test
    const downloadSpy = spyOn<CompressionPreviewComponent, any>(component, '_downloadBlob');

    component.compress();
    expect(component.state()).toBe('compressing');

    const compressReq = httpMock.expectOne('/api/compress');
    expect(compressReq.request.method).toBe('POST');

    const blob = makeSdcBlob();
    compressReq.flush(blob);
    tick(1100); // account for setTimeout in _downloadBlob
    fixture.detectChanges();

    expect(component.state()).toBe('done');
    expect(downloadSpy).toHaveBeenCalledWith(jasmine.any(Blob), jasmine.stringContaining('.sdc'));
  }));

  it('should calculate and display real ratio after successful compression', fakeAsync(() => {
    fixture.detectChanges();

    // 1 MB file, 256 KB blob => ratio = 4.0
    const file = makeFile('data.segy', 1024 * 1024);
    setFile(component, file);
    fixture.detectChanges();

    const benchmarkReq = httpMock.expectOne('/api/benchmark');
    benchmarkReq.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    spyOn<CompressionPreviewComponent, any>(component, '_downloadBlob');

    component.compress();

    const compressReq = httpMock.expectOne('/api/compress');
    const blob = makeSdcBlob(256 * 1024); // 256 KB
    compressReq.flush(blob);
    tick(1100);
    fixture.detectChanges();

    expect(component.state()).toBe('done');
    const realRatio = component.realRatio();
    expect(realRatio).not.toBeNull();
    expect(realRatio!).toBeCloseTo(4.0, 0);

    // Actual ratio label should appear in DOM
    const successValueEls = fixture.debugElement.queryAll(By.css('.info-row__value--success'));
    expect(successValueEls.length).toBeGreaterThan(0);
  }));

  it('should show progress bar during compression', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile());
    fixture.detectChanges();

    const benchmarkReq = httpMock.expectOne('/api/benchmark');
    benchmarkReq.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    spyOn<CompressionPreviewComponent, any>(component, '_downloadBlob');
    component.compress();
    fixture.detectChanges();

    // Progress bar should be visible during compressing state
    const progressBar = fixture.debugElement.query(By.css('mat-progress-bar'));
    expect(progressBar).toBeTruthy();

    // Flush to end the compressing state
    const compressReq = httpMock.expectOne('/api/compress');
    compressReq.flush(makeSdcBlob());
    tick(1100);
  }));

  it('should build correct .sdc filename from .segy input', () => {
    // Access private method via any cast
    const result = (component as any)._buildOutputFilename('seismic_data.segy');
    expect(result).toBe('seismic_data.sdc');

    const result2 = (component as any)._buildOutputFilename('data.sgy');
    expect(result2).toBe('data.sdc');

    const result3 = (component as any)._buildOutputFilename('survey.sgy2');
    expect(result3).toBe('survey.sdc');
  });

  // ──────────────────────────────────────────────
  // 4. Error handling for compress()
  // ──────────────────────────────────────────────

  it('should display error banner when POST /api/compress returns HTTP 500', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile());
    fixture.detectChanges();

    const benchmarkReq = httpMock.expectOne('/api/benchmark');
    benchmarkReq.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    component.compress();

    const compressReq = httpMock.expectOne('/api/compress');
    compressReq.flush(null, { status: 500, statusText: 'Internal Server Error' });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('error');
    expect(component.errorMessage()).not.toBeNull();

    const errorBanner = fixture.debugElement.query(By.css('.error-banner'));
    expect(errorBanner).toBeTruthy();
    const errorText = (errorBanner.nativeElement as HTMLElement).textContent ?? '';
    expect(errorText).toContain('500');
  }));

  it('should display error banner when POST /api/compress returns HTTP 400', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile('invalid.segy'));
    fixture.detectChanges();

    const benchmarkReq = httpMock.expectOne('/api/benchmark');
    benchmarkReq.flush({ compression_ratio: null, throughput_mb_s: 0 });
    tick();
    fixture.detectChanges();

    component.compress();

    const compressReq = httpMock.expectOne('/api/compress');
    compressReq.flush(null, { status: 400, statusText: 'Bad Request' });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('error');
    const errorBanner = fixture.debugElement.query(By.css('.error-banner'));
    expect(errorBanner).toBeTruthy();
  }));

  it('should allow re-compression after an error', fakeAsync(() => {
    fixture.detectChanges();

    setFile(component, makeFile());
    fixture.detectChanges();

    const benchmarkReq = httpMock.expectOne('/api/benchmark');
    benchmarkReq.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    component.compress();
    const firstCompressReq = httpMock.expectOne('/api/compress');
    firstCompressReq.flush(null, { status: 500, statusText: 'Internal Server Error' });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('error');

    // Compress again
    spyOn<CompressionPreviewComponent, any>(component, '_downloadBlob');
    component.compress();
    expect(component.state()).toBe('compressing');

    const secondCompressReq = httpMock.expectOne('/api/compress');
    secondCompressReq.flush(makeSdcBlob());
    tick(1100);
    fixture.detectChanges();

    expect(component.state()).toBe('done');
  }));

  // ──────────────────────────────────────────────
  // 5. Signal computed helpers
  // ──────────────────────────────────────────────

  it('should return null fileSizeLabel when no file', () => {
    fixture.detectChanges();
    expect(component.fileSizeLabel()).toBeNull();
  });

  it('should format file size in MB', () => {
    component.fileSize.set(2 * 1024 * 1024);
    expect(component.fileSizeLabel()).toBe('2.00 MB');
  });

  it('should format file size in KB for sub-MB files', () => {
    component.fileSize.set(512 * 1024);
    expect(component.fileSizeLabel()).toBe('512.00 KB');
  });

  it('should format file size in bytes for very small files', () => {
    component.fileSize.set(500);
    expect(component.fileSizeLabel()).toBe('500 B');
  });

  it('showProgress should be true during loading-benchmark', () => {
    component.state.set('loading-benchmark');
    expect(component.showProgress()).toBeTrue();
  });

  it('showProgress should be true during compressing', () => {
    component.state.set('compressing');
    expect(component.showProgress()).toBeTrue();
  });

  it('showProgress should be false in idle, ready, done, error states', () => {
    for (const s of ['idle', 'ready', 'done', 'error'] as const) {
      component.state.set(s);
      expect(component.showProgress()).toBeFalse();
    }
  });

  // ──────────────────────────────────────────────
  // 6. File change resets state properly
  // ──────────────────────────────────────────────

  it('should reset to idle when file input changes to null', fakeAsync(() => {
    fixture.detectChanges();

    const file = makeFile();
    setFile(component, file);
    fixture.detectChanges();

    const benchmarkReq = httpMock.expectOne('/api/benchmark');
    benchmarkReq.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('ready');

    // Now clear the file
    setFile(component, null, file);
    fixture.detectChanges();

    expect(component.state()).toBe('idle');
    expect(component.fileSize()).toBeNull();
    expect(component.benchmarkData()).toBeNull();
  }));
});
