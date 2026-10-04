import type { LinkItem } from '../../types/models';

interface LinksListProps {
  links: LinkItem[];
  /** shown before each link label */
  icon?: React.ReactNode;
}

export function LinksList({ links, icon }: LinksListProps) {
  if (links.length === 0) return null;

  return (
    <ul className="vm-links-list">
      {links.map((link) => (
        <li key={link.id}>
          <a href={link.url} target="_blank" rel="noopener noreferrer">
            {icon}
            {link.label}
          </a>
        </li>
      ))}
    </ul>
  );
}
