import type { Destination } from '../../types/models';

interface DestinationListSidebarProps {
  destinations: Destination[];
  selectedDestinationId: string | null;
  onSelect: (id: string) => void;
  onAddNew: () => void;
}

export function DestinationListSidebar({
  destinations,
  selectedDestinationId,
  onSelect,
  onAddNew,
}: DestinationListSidebarProps) {
  return (
    <div className="vm-destination-list">
      <div className="vm-destination-list-header">
        <h3>Destinations</h3>
        <button type="button" className="vm-btn-add" onClick={onAddNew}>
          + Add
        </button>
      </div>
      <ul>
        {destinations.map((dest) => (
          <li key={dest.id}>
            <button
              type="button"
              className={dest.id === selectedDestinationId ? 'vm-dest-list-item vm-dest-list-item-selected' : 'vm-dest-list-item'}
              onClick={() => onSelect(dest.id)}
            >
              {dest.name}
            </button>
          </li>
        ))}
        {destinations.length === 0 && <li className="vm-destination-list-empty">No destinations yet.</li>}
      </ul>
    </div>
  );
}
