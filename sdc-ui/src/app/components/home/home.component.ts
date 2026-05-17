import {
  Component,
  ViewChild,
  AfterViewInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  signal,
} from '@angular/core';
import { FileInspectorComponent } from '../file-inspector/file-inspector.component';
import { CompressionPreviewComponent } from '../compression-preview/compression-preview.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [FileInspectorComponent, CompressionPreviewComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeComponent implements AfterViewInit {
  @ViewChild('fileInspector') fileInspectorRef!: FileInspectorComponent;

  /** Currently selected file, forwarded to CompressionPreviewComponent */
  readonly selectedFile = signal<File | null>(null);

  constructor(private readonly _cdr: ChangeDetectorRef) {}

  ngAfterViewInit(): void {
    // Intercept FileInspectorComponent's file processing by wrapping its
    // internal _processFile method to capture the raw File reference.
    // FileInspectorComponent has no @Output, so we hook at the instance level.
    const inspector = this.fileInspectorRef;
    const original = (inspector as any)['_processFile'].bind(inspector);

    (inspector as any)['_processFile'] = (file: File) => {
      original(file);
      this.selectedFile.set(file);
      this._cdr.markForCheck();
    };
  }
}
