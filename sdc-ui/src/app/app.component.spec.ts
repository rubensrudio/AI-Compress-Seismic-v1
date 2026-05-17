import { TestBed } from '@angular/core/testing';
import { RouterModule } from '@angular/router';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AppComponent } from './app.component';
import { routes } from './app.routes';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        AppComponent,
        RouterModule.forRoot(routes),
        NoopAnimationsModule,
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render mat-toolbar with the application title', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const toolbar = compiled.querySelector('mat-toolbar');
    expect(toolbar).toBeTruthy();
    const titleLink = compiled.querySelector('.app-toolbar__title');
    expect(titleLink?.textContent?.trim()).toBe('AI-Compress Seismic Compressor');
  });

  it('should contain a link to halotechlabs.com', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const halotechlabsLink = compiled.querySelector('a.app-toolbar__halotechlabs-link');
    expect(halotechlabsLink).toBeTruthy();
    expect(halotechlabsLink?.getAttribute('href')).toBe('https://halotechlabs.com');
    expect(halotechlabsLink?.getAttribute('target')).toBe('_blank');
    expect(halotechlabsLink?.getAttribute('rel')).toContain('noopener');
  });

  it('should contain a router-outlet for routed views', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('router-outlet')).toBeTruthy();
  });

  it('should have a navigation link to /benchmark', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const benchmarkLink = Array.from(compiled.querySelectorAll('a')).find(
      (el) => el.getAttribute('href') === '/benchmark'
    );
    expect(benchmarkLink).toBeTruthy();
  });
});
