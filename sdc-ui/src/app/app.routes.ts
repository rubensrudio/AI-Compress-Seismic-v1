import { Routes } from '@angular/router';
import { HomeComponent } from './components/home/home.component';
import { BenchmarkComponent } from './components/benchmark/benchmark.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'benchmark', component: BenchmarkComponent },
  { path: '**', redirectTo: '' },
];
