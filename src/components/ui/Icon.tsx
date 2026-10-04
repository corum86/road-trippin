interface IconProps {
  /** Material Symbols ligature name, e.g. "arrow_back" */
  name: string;
  size?: number;
  filled?: boolean;
  className?: string;
}

export function Icon({ name, size, filled, className }: IconProps) {
  return (
    <span
      className={`vm-icon${filled ? ' vm-icon-filled' : ''}${className ? ` ${className}` : ''}`}
      style={size ? { fontSize: size } : undefined}
      aria-hidden="true"
    >
      {name}
    </span>
  );
}
