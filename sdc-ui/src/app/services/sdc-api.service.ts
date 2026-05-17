import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { HealthResponse, BenchmarkResponse } from '../models/sdc.models';

@Injectable({
  providedIn: 'root'
})
export class SdcApiService {
  private readonly baseUrl = '/api';

  constructor(private readonly http: HttpClient) {}

  compress(file: File): Observable<Blob> {
    return this.http
      .post(`${this.baseUrl}/compress`, file, {
        headers: { 'Content-Type': 'application/octet-stream' },
        responseType: 'blob'
      })
      .pipe(catchError(this.handleError));
  }

  decompress(file: File): Observable<Blob> {
    return this.http
      .post(`${this.baseUrl}/decompress`, file, {
        headers: { 'Content-Type': 'application/octet-stream' },
        responseType: 'blob'
      })
      .pipe(catchError(this.handleError));
  }

  getHealth(): Observable<HealthResponse> {
    return this.http
      .get<HealthResponse>(`${this.baseUrl}/health`)
      .pipe(catchError(this.handleError));
  }

  getBenchmark(): Observable<BenchmarkResponse> {
    return this.http
      .get<BenchmarkResponse>(`${this.baseUrl}/benchmark`)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    return throwError(() => error);
  }
}
