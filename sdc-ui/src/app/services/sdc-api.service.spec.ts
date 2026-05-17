import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController
} from '@angular/common/http/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { SdcApiService } from './sdc-api.service';
import { HealthResponse, BenchmarkResponse } from '../models/sdc.models';

describe('SdcApiService', () => {
  let service: SdcApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [SdcApiService]
    });
    service = TestBed.inject(SdcApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('compress()', () => {
    it('should POST to /api/compress and return a Blob', (done) => {
      const fakeFile = new File(['seismic-data'], 'test.segy', {
        type: 'application/octet-stream'
      });
      const fakeBlob = new Blob(['compressed-data'], {
        type: 'application/octet-stream'
      });

      service.compress(fakeFile).subscribe({
        next: (result: Blob) => {
          expect(result).toBeInstanceOf(Blob);
          done();
        },
        error: done.fail
      });

      const req = httpMock.expectOne('/api/compress');
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Content-Type')).toBe(
        'application/octet-stream'
      );
      req.flush(fakeBlob);
    });

    it('should propagate HTTP 500 error as Observable error with HttpErrorResponse', (done) => {
      const fakeFile = new File(['seismic-data'], 'test.segy', {
        type: 'application/octet-stream'
      });

      service.compress(fakeFile).subscribe({
        next: () => done.fail('Expected error, not success'),
        error: (err: HttpErrorResponse) => {
          expect(err).toBeInstanceOf(HttpErrorResponse);
          expect(err.status).toBe(500);
          done();
        }
      });

      const req = httpMock.expectOne('/api/compress');
      req.flush(null, {
        status: 500,
        statusText: 'Internal Server Error'
      });
    });

    it('should propagate HTTP 400 error as Observable error with HttpErrorResponse', (done) => {
      const fakeFile = new File(['invalid-data'], 'invalid.segy', {
        type: 'application/octet-stream'
      });

      service.compress(fakeFile).subscribe({
        next: () => done.fail('Expected error, not success'),
        error: (err: HttpErrorResponse) => {
          expect(err).toBeInstanceOf(HttpErrorResponse);
          expect(err.status).toBe(400);
          done();
        }
      });

      const req = httpMock.expectOne('/api/compress');
      req.flush(null, { status: 400, statusText: 'Bad Request' });
    });
  });

  describe('decompress()', () => {
    it('should POST to /api/decompress and return a Blob', (done) => {
      const fakeFile = new File(['compressed-data'], 'test.sdc', {
        type: 'application/octet-stream'
      });
      const fakeBlob = new Blob(['decompressed-segy'], {
        type: 'application/octet-stream'
      });

      service.decompress(fakeFile).subscribe({
        next: (result: Blob) => {
          expect(result).toBeInstanceOf(Blob);
          done();
        },
        error: done.fail
      });

      const req = httpMock.expectOne('/api/decompress');
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Content-Type')).toBe(
        'application/octet-stream'
      );
      req.flush(fakeBlob);
    });

    it('should propagate HTTP 500 error for decompress as HttpErrorResponse', (done) => {
      const fakeFile = new File(['compressed-data'], 'test.sdc', {
        type: 'application/octet-stream'
      });

      service.decompress(fakeFile).subscribe({
        next: () => done.fail('Expected error, not success'),
        error: (err: HttpErrorResponse) => {
          expect(err).toBeInstanceOf(HttpErrorResponse);
          expect(err.status).toBe(500);
          done();
        }
      });

      const req = httpMock.expectOne('/api/decompress');
      req.flush(null, {
        status: 500,
        statusText: 'Internal Server Error'
      });
    });
  });

  describe('getHealth()', () => {
    it('should GET /api/health and return HealthResponse', (done) => {
      const mockResponse: HealthResponse = {
        status: 'UP',
        timestamp: '2026-05-16T00:00:00Z'
      };

      service.getHealth().subscribe({
        next: (result: HealthResponse) => {
          expect(result.status).toBe('UP');
          expect(result.timestamp).toBe('2026-05-16T00:00:00Z');
          done();
        },
        error: done.fail
      });

      const req = httpMock.expectOne('/api/health');
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

    it('should propagate HTTP 503 error for getHealth as HttpErrorResponse', (done) => {
      service.getHealth().subscribe({
        next: () => done.fail('Expected error, not success'),
        error: (err: HttpErrorResponse) => {
          expect(err).toBeInstanceOf(HttpErrorResponse);
          expect(err.status).toBe(503);
          done();
        }
      });

      const req = httpMock.expectOne('/api/health');
      req.flush('Service Unavailable', {
        status: 503,
        statusText: 'Service Unavailable'
      });
    });
  });

  describe('getBenchmark()', () => {
    it('should GET /api/benchmark and return BenchmarkResponse', (done) => {
      const mockResponse: BenchmarkResponse = {
        compression_ratio: 3.5,
        throughput_mb_s: 76.6,
        dataset_size_gb: 1.71,
        timestamp: '2026-05-16T00:00:00Z'
      };

      service.getBenchmark().subscribe({
        next: (result: BenchmarkResponse) => {
          expect(result.compression_ratio).toBe(3.5);
          expect(result.throughput_mb_s).toBe(76.6);
          done();
        },
        error: done.fail
      });

      const req = httpMock.expectOne('/api/benchmark');
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

    it('should propagate HTTP 500 error for getBenchmark as HttpErrorResponse', (done) => {
      service.getBenchmark().subscribe({
        next: () => done.fail('Expected error, not success'),
        error: (err: HttpErrorResponse) => {
          expect(err).toBeInstanceOf(HttpErrorResponse);
          expect(err.status).toBe(500);
          done();
        }
      });

      const req = httpMock.expectOne('/api/benchmark');
      req.flush('Internal Server Error', {
        status: 500,
        statusText: 'Internal Server Error'
      });
    });
  });
});
