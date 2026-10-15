import { useState } from 'react';
import styles from './QlAvatar.module.css';

type AvatarSize = 'sm' | 'md' | 'lg';

interface QlAvatarProps {
  src?: string;
  alt?: string;
  initials?: string;
  size?: AvatarSize;
  testId?: string;
}

export function QlAvatar({ src, alt = '', initials, size = 'md', testId }: QlAvatarProps) {
  const [imgError, setImgError] = useState(false);
  const showImage = src && !imgError;
  const derivedTestId = testId ?? (showImage ? 'ql-avatar-img' : initials ? 'ql-avatar-initials' : `ql-avatar-${size}`);
  return (
    <div data-testid={derivedTestId} className={`${styles.avatar} ${styles[size]}`} aria-label={alt || initials || 'Avatar'}>
      {showImage ? (
        <img src={src} alt={alt} className={styles.img} onError={() => setImgError(true)} />
      ) : (
        <span className={styles.initials} aria-hidden="true">{initials ?? '?'}</span>
      )}
    </div>
  );
}
