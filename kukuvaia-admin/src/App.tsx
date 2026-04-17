import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import { DashboardPage } from './pages/DashboardPage';
import { ProvidersPage } from './pages/ProvidersPage';
import { ModelsPage } from './pages/ModelsPage';
import { RolesPage } from './pages/RolesPage';
import { DreamPage } from './pages/DreamPage';

export default function App() {
  return (
    <BrowserRouter>
      <nav className="nav">
        <NavLink to="/" className={({ isActive }) => isActive ? 'active' : ''} end>Dashboard</NavLink>
        <NavLink to="/providers" className={({ isActive }) => isActive ? 'active' : ''}>Providers</NavLink>
        <NavLink to="/models" className={({ isActive }) => isActive ? 'active' : ''}>Models</NavLink>
        <NavLink to="/roles" className={({ isActive }) => isActive ? 'active' : ''}>Roles</NavLink>
        <NavLink to="/dream" className={({ isActive }) => isActive ? 'active' : ''}>Dreaming</NavLink>
      </nav>
      <main className="page">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/providers" element={<ProvidersPage />} />
          <Route path="/models" element={<ModelsPage />} />
          <Route path="/roles" element={<RolesPage />} />
          <Route path="/dream" element={<DreamPage />} />
        </Routes>
      </main>
    </BrowserRouter>
  );
}
