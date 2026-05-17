import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
} from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { HomeComponent } from './home.component';
import { FileInspectorComponent } from '../file-inspector/file-inspector.component';
import { CompressionPreviewComponent } from '../compression-preview/compression-preview.component';
import { SdcApiService } from '../../services/sdc-api.service';

describe('HomeComponent', () => {
  let fixture: ComponentFixture<HomeComponent>;
  let component: HomeComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        HomeComponent,
        HttpClientTestingModule,
        NoopAnimationsModule,
      ],
      providers: [SdcApiService],
    }).compileComponents();

    fixture = TestBed.createComponent(HomeComponent);
    component = fixture.componentInstance;
  });

  it('should create without error', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should render FileInspectorComponent in the template', () => {
    fixture.detectChanges();
    const inspector = fixture.debugElement.query(By.directive(FileInspectorComponent));
    expect(inspector).toBeTruthy();
  });

  it('should render CompressionPreviewComponent in the template', () => {
    fixture.detectChanges();
    const preview = fixture.debugElement.query(By.directive(CompressionPreviewComponent));
    expect(preview).toBeTruthy();
  });

  it('should initialise selectedFile signal to null', () => {
    fixture.detectChanges();
    expect(component.selectedFile()).toBeNull();
  });

  it('should have a CSS grid layout container', () => {
    fixture.detectChanges();
    const layout = fixture.debugElement.query(By.css('.home-layout'));
    expect(layout).toBeTruthy();
  });

  it('should have inspector and preview slots within the layout', () => {
    fixture.detectChanges();
    const inspectorSlot = fixture.debugElement.query(By.css('.home-layout__inspector'));
    const previewSlot = fixture.debugElement.query(By.css('.home-layout__preview'));
    expect(inspectorSlot).toBeTruthy();
    expect(previewSlot).toBeTruthy();
  });

  it('should update selectedFile signal when set directly', () => {
    fixture.detectChanges();
    const mockFile = new File(['data'], 'test.segy', { type: 'application/octet-stream' });
    component.selectedFile.set(mockFile);
    expect(component.selectedFile()).toBe(mockFile);
  });

  it('should clear selectedFile signal when set to null', () => {
    fixture.detectChanges();
    const mockFile = new File(['data'], 'test.segy', { type: 'application/octet-stream' });
    component.selectedFile.set(mockFile);
    component.selectedFile.set(null);
    expect(component.selectedFile()).toBeNull();
  });
});
