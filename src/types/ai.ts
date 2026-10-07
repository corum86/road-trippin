export interface AiPhoto {
  imageUrl: string;
  /** the page the photo comes from */
  sourceUrl: string;
  sourceTitle: string;
}

export interface AiLink {
  label: string;
  url: string;
}

/** One researched sight or thing to do: shown as a card with its photo, text and link. */
export interface AiFinding {
  id: string;
  /** the sight or activity; empty when the model only returned a sentence */
  name: string;
  text: string;
  photo?: AiPhoto;
  link?: AiLink;
  added: boolean;
}

export interface DestinationAiResult {
  destinationId: string;
  destinationName: string;
  status: 'success' | 'error';
  error?: string;
  findings: AiFinding[];
}
