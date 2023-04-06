const tweetForm = document.getElementById('tweet-form');
const tweetText = document.getElementById('tweet-text');
const tweetsContainer = document.getElementById('tweets-container');

tweetForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const tweetContent = tweetText.value.trim();
    if (tweetContent) {
        const tweetElement = document.createElement('div');
        tweetElement.textContent = tweetContent;
        tweetsContainer.prepend(tweetElement);
        tweetText.value = '';
    }
});
