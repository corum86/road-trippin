export type AiFindingKind = 'image' | 'text' | 'link';

export interface AiImageFinding {
  id: string;
  kind: 'image';
  imageUrl: string;
  sourceUrl: string;
  sourceTitle: string;
  added: boolean;
}

export interface AiTextFinding {
  id: string;
  kind: 'text';
  fact: string;
  added: boolean;
}

export interface AiLinkFinding {
  id: string;
  kind: 'link';
  label: string;
  url: string;
  added: boolean;
}

export type AiFinding = AiImageFinding | AiTextFinding | AiLinkFinding;

export interface DestinationAiResult {
  destinationId: string;
  destinationName: string;
  status: 'success' | 'error';
  error?: string;
  images: AiImageFinding[];
  texts: AiTextFinding[];
  links: AiLinkFinding[];
}
