import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { BenchmarkComponent } from './benchmark.component';
import { SdcApiService } from '../../services/sdc-api.service';

describe('BenchmarkComponent', () => {
  let fixture: ComponentFixture<BenchmarkComponent>;
  let component: BenchmarkComponent;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        BenchmarkComponent,
        HttpClientTestingModule,
        NoopAnimationsModule,
      ],
      providers: [SdcApiService],
    }).compileComponents();

    fixture = TestBed.createComponent(BenchmarkComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ──────────────────────────────────────────────
  // 1. Loading state
  // ──────────────────────────────────────────────

  it('should create the component', fakeAsync(() => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
    // Flush the pending request to clean up
    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: null, throughput_mb_s: 0 });
    tick();
  }));

  it('should start in loading state and show mat-progress-bar', fakeAsync(() => {
    fixture.detectChanges();
    expect(component.state()).toBe('loading');
    const progressBar = fixture.debugElement.query(By.css('mat-progress-bar'));
    expect(progressBar).toBeTruthy();
    // Cleanup
    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: null, throughput_mb_s: 0 });
    tick();
  }));

  it('should call GET /api/benchmark on init', fakeAsync(() => {
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/benchmark');
    expect(req.request.method).toBe('GET');
    req.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();
    expect(component.state()).toBe('success');
  }));

  // ──────────────────────────────────────────────
  // 2. Success state
  // ──────────────────────────────────────────────

  it('should display rows in mat-table on success', fakeAsync(() => {
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('success');
    expect(component.rows().length).toBeGreaterThan(0);
    const table = fixture.debugElement.query(By.css('mat-table'));
    expect(table).toBeTruthy();
  }));

  it('should display throughput value in the table', fakeAsync(() => {
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    const rows = component.rows();
    const throughputRow = rows.find((r) => r.field.toLowerCase().includes('throughput'));
    expect(throughputRow).toBeTruthy();
    expect(throughputRow?.value).toContain('76.6');
  }));

  it('should display compression_ratio correctly when provided', fakeAsync(() => {
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: 4.25, throughput_mb_s: 80.0 });
    tick();
    fixture.detectChanges();

    const rows = component.rows();
    const ratioRow = rows.find((r) => r.field.toLowerCase().includes('compression'));
    expect(ratioRow).toBeTruthy();
    expect(ratioRow?.value).toContain('4.25');
  }));

  it('should display "N/A" for compression_ratio when null', fakeAsync(() => {
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush({ compression_ratio: null, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    const rows = component.rows();
    const ratioRow = rows.find((r) => r.field.toLowerCase().includes('compression'));
    expect(ratioRow).toBeTruthy();
    expect(ratioRow?.value).toBe('N/A');
  }));

  it('should include optional fields when present in response', fakeAsync(() => {
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush({
      compression_ratio: 3.5,
      throughput_mb_s: 76.6,
      dataset_size_gb: 1.71,
      version: '1.0.0',
      timestamp: '2026-05-16T00:00:00Z',
    });
    tick();
    fixture.detectChanges();

    const rows = component.rows();
    expect(rows.find((r) => r.field.includes('Dataset Size'))).toBeTruthy();
    expect(rows.find((r) => r.field.includes('Version'))).toBeTruthy();
    expect(rows.find((r) => r.field.includes('Timestamp'))).toBeTruthy();
  }));

  // ──────────────────────────────────────────────
  // 3. Error state
  // ──────────────────────────────────────────────

  it('should show error message when GET /api/benchmark fails', fakeAsync(() => {
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush('Internal Server Error', {
      status: 500,
      statusText: 'Internal Server Error',
    });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('error');
    expect(component.errorMessage()).not.toBeNull();

    const errorEl = fixture.debugElement.query(By.css('.benchmark-error'));
    expect(errorEl).toBeTruthy();
  }));

  it('should not show mat-table when in error state', fakeAsync(() => {
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush(null, { status: 503, statusText: 'Service Unavailable' });
    tick();
    fixture.detectChanges();

    const table = fixture.debugElement.query(By.css('mat-table'));
    expect(table).toBeNull();
  }));

  it('should retry request when retry() is called', fakeAsync(() => {
    fixture.detectChanges();

    // First request fails
    const firstReq = httpMock.expectOne('/api/benchmark');
    firstReq.flush(null, { status: 500, statusText: 'Internal Server Error' });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('error');

    // Retry
    component.retry();
    fixture.detectChanges();

    expect(component.state()).toBe('loading');

    const secondReq = httpMock.expectOne('/api/benchmark');
    secondReq.flush({ compression_ratio: 3.5, throughput_mb_s: 76.6 });
    tick();
    fixture.detectChanges();

    expect(component.state()).toBe('success');
  }));

  it('should show the error message text in the DOM', fakeAsync(() => {
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/benchmark');
    req.flush(null, { status: 503, statusText: 'Service Unavailable' });
    tick();
    fixture.detectChanges();

    const errorEl = fixture.debugElement.query(By.css('.benchmark-error__message'));
    expect(errorEl).toBeTruthy();
    const text = (errorEl.nativeElement as HTMLElement).textContent ?? '';
    expect(text.length).toBeGreaterThan(0);
  }));
});
