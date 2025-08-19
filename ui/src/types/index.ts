import type { Metadata } from "@halo-dev/api-client";


export interface ListedMailTemplateList {
  page: number;
  size: number;
  total: number;
  totalPages: number;
  items: Array<ListedMailTemplate>;
  first: boolean;
  last: boolean;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface ListedMailTemplate {
  'group': MailTemplateGroup;
  'mailTemplate': MailTemplate;
  'owner'?: Contributor;
}

export interface MailTemplateGroup {
  'apiVersion': string;
  'kind': string;
  'metadata': Metadata;
  'spec': MailTemplateGroupSpec;
  'status'?: MailTemplateGroupStatus;
}

export interface MailTemplateGroupSpec {
  'displayName': string;
  'priority'?: number;
}

export interface MailTemplateGroupStatus {
  'mailTemplateCount'?: number;
}


export interface MailTemplate {
  'apiVersion': string;
  'kind': string;
  'metadata': Metadata;
  'spec': MailTemplateSpec;
}

export interface MailTemplateSpec {
  'approved': boolean;
  'approvedTime'?: string;
  'cover'?: string;
  'description'?: string;
  'displayName': string;
  'groupName': string;
  'htmlBody': string;
  'owner'?: string;
  'priority': number;
}
export interface Contributor {
  'avatar'?: string;
  'displayName'?: string;
  'name'?: string;
}
