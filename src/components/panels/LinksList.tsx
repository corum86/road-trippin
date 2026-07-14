import type { LinkItem } from '../../types/models';

interface LinksListProps {
  links: LinkItem[];
}

export function LinksList({ links }: LinksListProps) {
  if (links.length === 0) return null;

  return (
    <ul className="vm-links-list">
      {links.map((link) => (
        <li key={link.id}>
          <a href={link.url} target="_blank" rel="noopener noreferrer">
            {link.label}
          </a>
        </li>
      ))}
    </ul>
  );
}
